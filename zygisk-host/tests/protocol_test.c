#define _GNU_SOURCE
#include "protocol.h"
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

struct send_args { int socket, artifact; };
static void *send_fd(void *data) {
    struct send_args *a = data;
    struct eh_reply reply = {EH_PROTOCOL, 2, 1, 0};
    assert(eh_send(a->socket, &reply, "{}", &a->artifact) == 0);
    close(a->socket); return NULL;
}
static void fd_roundtrip(void) {
    int sockets[2]; assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    int artifact = open("/dev/null", O_RDONLY); assert(artifact >= 0);
    struct send_args args = {sockets[0], artifact};
    pthread_t writer; assert(pthread_create(&writer, NULL, send_fd, &args) == 0);
    struct eh_reply reply; char json[EH_MAX_JSON]; int fds[EH_MAX_PLUGINS];
    assert(eh_receive(sockets[1], &reply, json, fds) == 0);
    assert(!strcmp(json, "{}") && reply.fd_count == 1);
    assert(fcntl(fds[0], F_GETFD) & FD_CLOEXEC);
    char byte; assert(read(fds[0], &byte, 1) == 0);
    close(fds[0]); close(sockets[1]); close(artifact); pthread_join(writer, NULL);
}
static void rejected_envelope(void) {
    int sockets[2]; assert(socketpair(AF_UNIX, SOCK_STREAM, 0, sockets) == 0);
    struct eh_reply reply = {EH_PROTOCOL + 1, 0, 0, 0};
    assert(eh_send(sockets[0], &reply, NULL, NULL) == 0);
    char json[EH_MAX_JSON]; int fds[EH_MAX_PLUGINS];
    assert(eh_receive(sockets[1], &reply, json, fds) == -1);
    close(sockets[0]); close(sockets[1]);
}
int main(void) {
    assert(eh_identifier("plugin-1") && !eh_identifier("../x") && !eh_identifier(""));
    assert(eh_package("io.github.demo") && !eh_package("../demo") && !eh_package("io..demo"));
    cJSON *p = cJSON_Parse("{\"processScope\":\"all\",\"processName\":\"io.github.demo\"}");
    assert(eh_matches(p, "io.github.demo", "io.github.demo"));
    assert(eh_matches(p, "io.github.demo", "io.github.demo:worker"));
    assert(!eh_matches(p, "io.github.demo", "io.github.demo2:worker"));
    assert(!eh_matches(p, "io.github.demo", "io.github.other"));
    cJSON_ReplaceItemInObject(p, "processScope", cJSON_CreateString("children"));
    assert(!eh_matches(p, "io.github.demo", "io.github.demo"));
    cJSON_Delete(p);
    fd_roundtrip(); rejected_envelope(); puts("protocol boundary and FD transfer passed");
    return 0;
}
