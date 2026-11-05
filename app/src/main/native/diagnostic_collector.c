#define _GNU_SOURCE
#include <errno.h>
#include <inttypes.h>
#include <poll.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include "diagnostic_wire.h"

struct client { int fd; pid_t pid; uint64_t ticks; char process[256]; };
static uint64_t elapsed(void) { struct timespec t; clock_gettime(CLOCK_BOOTTIME, &t); return (uint64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000; }
static void string(const char *value) {
    putchar('"');
    for (const unsigned char *p = (const unsigned char *)value; *p; p++) {
        if (*p == '"' || *p == '\\') { putchar('\\'); putchar(*p); }
        else if (*p < 32) printf("\\u%04x", *p);
        else putchar(*p);
    }
    putchar('"');
}
static int identity(struct client *client, uid_t uid, const char *package, const char *scope) {
    struct ucred peer; socklen_t length = sizeof(peer);
    if (getsockopt(client->fd, SOL_SOCKET, SO_PEERCRED, &peer, &length) != 0 || peer.uid != uid) return -1;
    client->pid = peer.pid;
    char path[64]; snprintf(path, sizeof(path), "/proc/%d/cmdline", peer.pid);
    FILE *input = fopen(path, "r"); if (!input) return -1;
    size_t count = fread(client->process, 1, sizeof(client->process) - 1, input); fclose(input); client->process[count] = 0;
    size_t prefix = strlen(package);
    if (strncmp(client->process, package, prefix) || (client->process[prefix] && client->process[prefix] != ':')) return -1;
    if (strcmp(scope, "*") && strcmp(client->process, scope)) return -1;
    snprintf(path, sizeof(path), "/proc/%d/stat", peer.pid);
    input = fopen(path, "r"); if (!input) return -1;
    char stat[2048]; char *line = fgets(stat, sizeof(stat), input); fclose(input); if (!line) return -1;
    char *start = strrchr(stat, ')'); if (!start) return -1;
    char *state = NULL; char *field = strtok_r(start + 2, " ", &state);
    for (int i = 3; field && i < 22; i++) field = strtok_r(NULL, " ", &state);
    if (!field) return -1;
    client->ticks = strtoull(field, NULL, 10);
    return 0;
}
static void event(const struct client *client, uid_t uid, const struct sh_diag_event *e) {
    const char *phase[] = {"", "native_initialized", "native_probe", "library_existing", "library_before_init", "library_after_init", "native_exit_call", "library_before_fini"};
    printf("{\"phase\":\"%s\",\"pid\":%d,\"uid\":%u,\"tid\":%u,\"startTicks\":%" PRIu64 ",\"processName\":", phase[e->kind], client->pid, uid, e->tid, client->ticks);
    string(client->process);
    printf(",\"sequence\":%" PRIu64 ",\"droppedBefore\":%" PRIu64 ",\"wallTime\":%" PRIu64 ",\"elapsedRealtime\":%" PRIu64 ",\"name\":", e->sequence, e->dropped, e->wall_ms, e->elapsed_ms);
    string(e->name);
    printf(",\"nameTruncated\":%s", e->flags & 1 ? "true" : "false");
    if ((e->kind >= SH_EXISTING && e->kind <= SH_AFTER_INIT) || e->kind == SH_BEFORE_FINI) printf(",\"loadBias\":\"0x%" PRIx64 "\",\"start\":\"0x%" PRIx64 "\",\"end\":\"0x%" PRIx64 "\"", e->a, e->b, e->c);
    else if (e->kind == SH_EXIT) printf(",\"callerReturnAddress\":\"0x%" PRIx64 "\",\"argument0\":%" PRId64 ",\"argument1\":%" PRId64 ",\"argument2\":%" PRId64, e->pc, (int64_t)e->a, (int64_t)e->b, (int64_t)e->c);
    else printf(",\"errorCode\":%" PRId64, (int64_t)e->a);
    if (e->kind == SH_INIT) printf(",\"sendBufferBytes\":%" PRIu64, e->b);
    puts("}"); fflush(stdout);
}
int main(int argc, char **argv) {
    if (argc != 7) { fprintf(stderr, "socket uid package process seconds ready-file\n"); return 2; }
    uid_t uid = (uid_t)strtoul(argv[2], NULL, 10);
    unsigned duration = (unsigned)strtoul(argv[5], NULL, 10);
    if (uid < 10000 || duration == 0 || duration > 180 || strlen(argv[1]) > 100) return 2;
    int server = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    struct sockaddr_un address = { .sun_family = AF_UNIX }; size_t length = strlen(argv[1]); memcpy(address.sun_path + 1, argv[1], length);
    if (server < 0 || bind(server, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + length + 1) || listen(server, 16)) { perror("native collector listen"); return 1; }
    FILE *ready = fopen(argv[6], "w"); if (!ready) { perror("ready"); return 1; } fputs("ready", ready); fclose(ready);
    struct client clients[32] = {0}; for (int i = 0; i < 32; i++) clients[i].fd = -1;
    uint64_t accepted = 0, discarded = 0;
    uint64_t until = elapsed() + duration * 1000;
    while (elapsed() < until) {
        struct pollfd pollfds[33] = {{.fd = server, .events = POLLIN}};
        for (int i = 0; i < 32; i++) { pollfds[i + 1].fd = clients[i].fd; pollfds[i + 1].events = POLLIN; }
        int result = poll(pollfds, 33, 100);
        if (result < 0) { if (errno == EINTR) continue; perror("poll"); return 1; }
        if (pollfds[0].revents & POLLIN) {
            int fd = accept4(server, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
            if (fd >= 0) {
                int index = 0; while (index < 32 && clients[index].fd >= 0) index++;
                if (index == 32) { close(fd); fprintf(stderr, "native client capacity reached\n"); }
                else { clients[index].fd = fd; if (identity(&clients[index], uid, argv[3], argv[4])) { close(fd); clients[index].fd = -1; fprintf(stderr, "native peer rejected\n"); } }
            }
        }
        for (int i = 0; i < 32; i++) if (clients[i].fd >= 0 && pollfds[i + 1].revents) {
            struct sh_diag_event e; ssize_t count;
            while ((count = recv(clients[i].fd, &e, sizeof(e), MSG_DONTWAIT | MSG_TRUNC)) > 0) {
                if (count != (ssize_t)sizeof(e) || e.version != SH_DIAG_VERSION || e.kind < SH_INIT || e.kind > SH_BEFORE_FINI || e.pid != (uint32_t)clients[i].pid || e.name[sizeof(e.name) - 1]) { fprintf(stderr, "native packet rejected\n"); continue; }
                if (accepted++ < 50000) event(&clients[i], uid, &e); else { if (discarded++ == 0) fprintf(stderr, "native event storage limit reached (50000); subsequent events discarded\n"); }
            }
            if (count == 0 || (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK)) { close(clients[i].fd); clients[i].fd = -1; }
        }
    }
    for (int i = 0; i < 32; i++) if (clients[i].fd >= 0) close(clients[i].fd);
    if (discarded) fprintf(stderr, "native event storage limit: discarded=%" PRIu64 "\n", discarded);
    close(server); return 0;
}
