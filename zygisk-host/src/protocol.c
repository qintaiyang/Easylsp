#define _GNU_SOURCE
#include "protocol.h"
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

int eh_boot_id(char out[64]) {
    FILE *file = fopen("/proc/sys/kernel/random/boot_id", "re");
    if (!file) return -1;
    char *result = fgets(out, 64, file); fclose(file);
    if (!result) return -1;
    out[strcspn(out, "\r\n")] = 0; return 0;
}
int eh_identifier(const char *s) {
    if (!s || !*s || strlen(s) > 63) return 0;
    for (; *s; s++) if (!((*s >= 'a' && *s <= 'z') || (*s >= 'A' && *s <= 'Z') || (*s >= '0' && *s <= '9') || *s == '-' || *s == '_')) return 0;
    return 1;
}
int eh_package(const char *s) {
    if (!s || !*s || strlen(s) > 255 || !strchr(s, '.') || s[0] == '.' || s[strlen(s)-1] == '.') return 0;
    for (; *s; s++) if (!((*s >= 'a' && *s <= 'z') || (*s >= 'A' && *s <= 'Z') || (*s >= '0' && *s <= '9') || *s == '_' || (*s == '.' && s[1] != '.'))) return 0;
    return 1;
}
const char *eh_string(const cJSON *o, const char *key) { return cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(o, key)); }
static int integer(const cJSON *o, const char *key, double low, double high) {
    const cJSON *n = cJSON_GetObjectItemCaseSensitive(o, key);
    return cJSON_IsNumber(n) && n->valuedouble >= low && n->valuedouble <= high && n->valuedouble == (double)(uint64_t)n->valuedouble;
}
#define REQUIRE(condition, message) do { if (!(condition)) { snprintf(error, capacity, "%s", message); return -EINVAL; } } while (0)
int eh_validate(const cJSON *s, char *error, size_t capacity) {
    REQUIRE(cJSON_IsObject(s), "snapshot must be an object");
    REQUIRE(integer(s, "schema", 1, 1) && integer(s, "hostProtocolVersion", EH_PROTOCOL, EH_PROTOCOL), "unsupported host protocol");
    REQUIRE(eh_identifier(eh_string(s, "generation")) && eh_identifier(eh_string(s, "publishRequestId")), "invalid generation/request identity");
    const char *backend = eh_string(s, "backend"), *policy = eh_string(s, "policy");
    REQUIRE(backend && !strcmp(backend, "ZYGISK"), "backend must be ZYGISK");
    REQUIRE(policy && (!strcmp(policy, "NATIVE_ONLY") || !strcmp(policy, "NATIVE_AND_JAVA")), "invalid policy");
    REQUIRE(eh_package(eh_string(s, "packageName")) && integer(s, "uid", 10000, 1999999999) && integer(s, "userId", 0, 19999), "invalid target identity");
    int uid = cJSON_GetObjectItemCaseSensitive(s, "uid")->valueint;
    REQUIRE(uid / 100000 == cJSON_GetObjectItemCaseSensitive(s, "userId")->valueint && uid % 100000 >= 10000 && uid % 100000 < 20000, "only ordinary application UIDs are supported");
    REQUIRE(integer(s, "targetVersionCode", 0, 9007199254740991.0), "invalid target version");
    const char *identity = eh_string(s, "targetInstallIdentity");
    REQUIRE(identity && identity[0] == '/' && strlen(identity) < 1024, "missing local targetInstallIdentity (base APK path)");
    const cJSON *diagnostic = cJSON_GetObjectItemCaseSensitive(s, "diagnostic");
    if (diagnostic) {
        const char *mode = eh_string(diagnostic, "mode"), *process = eh_string(diagnostic, "processName"), *endpoint = eh_string(diagnostic, "nativeSocket");
        REQUIRE(eh_identifier(eh_string(diagnostic, "diagnosticRunId")) && integer(diagnostic, "expiresAt", 1, 9007199254740991.0) && integer(diagnostic, "expiresElapsed", 1, 9007199254740991.0), "invalid diagnostic identity/deadline");
        REQUIRE(mode && (!strcmp(mode, "ENTRY_ONLY") || !strcmp(mode, "NATIVE_OBSERVE") || !strcmp(mode, "JAVA_NO_PLUGINS") || !strcmp(mode, "NORMAL")), "invalid diagnostic mode");
        REQUIRE(process && strlen(process) < 256, "invalid diagnostic process scope");
        REQUIRE(strcmp(mode, "NATIVE_OBSERVE") || (endpoint && strlen(endpoint) < 107 && !strncmp(endpoint, "easylsp", 7)), "missing native diagnostic endpoint");
    }
    const cJSON *plugins = cJSON_GetObjectItemCaseSensitive(s, "orderedPlugins");
    REQUIRE(cJSON_IsArray(plugins) && cJSON_GetArraySize(plugins) <= EH_MAX_PLUGINS, "invalid plugin count");
    const cJSON *p;
    cJSON_ArrayForEach(p, plugins) {
        REQUIRE(eh_identifier(eh_string(p, "localPluginId")) && eh_identifier(eh_string(p, "buildId")), "invalid artifact identity");
        const char *scope = eh_string(p, "processScope"), *process = eh_string(p, "processName");
        REQUIRE(scope && (!strcmp(scope, "main") || !strcmp(scope, "children") || !strcmp(scope, "all") || !strcmp(scope, "exact")), "invalid process scope");
        const char *type = eh_string(p,"processType");
        REQUIRE(!type || !strcmp(type,"app") || !strcmp(type,"isolated") || !strcmp(type,"app_zygote"), "invalid process type");
        REQUIRE(!type || !strcmp(type,"app") || (!strcmp(scope,"exact") && !strcmp(policy,"NATIVE_ONLY")), "special processes require exact Native-only scope");
        REQUIRE(process && strlen(process) < 256 && (!strcmp(scope, "exact") ? *process != 0 : 1), "missing processName");
        const char *package = eh_string(s, "packageName");
        REQUIRE(strcmp(scope,"exact") || ((!type || !strcmp(type,"app")) && !strcmp(process,package)) || (!strncmp(process,package,strlen(package)) && process[strlen(package)] == ':' && process[strlen(package) + 1]), "exact process must belong to target package; WebView targets its provider");
        REQUIRE(integer(p, "sourceRevision", 0, 9007199254740991.0), "invalid source revision");
        const cJSON *config = cJSON_GetObjectItemCaseSensitive(p, "config");
        REQUIRE(cJSON_IsObject(config), "config must be an object");
        char *serialized = cJSON_PrintUnformatted(config);
        int fits = serialized && strlen(serialized) <= 8192;
        free(serialized);
        REQUIRE(fits, "config exceeds 8 KiB");
        const cJSON *before;
        for (before = plugins->child; before != p; before = before->next)
            REQUIRE(strcmp(eh_string(before, "localPluginId"), eh_string(p, "localPluginId")), "duplicate plugin identity");
    }
    return 0;
}
int eh_validate_native(const cJSON *s, char *error, size_t capacity) {
    REQUIRE(cJSON_IsObject(s) && integer(s,"schema",1,1), "invalid Native entry schema");
    REQUIRE(eh_identifier(eh_string(s,"targetId")) && eh_identifier(eh_string(s,"generation")), "invalid Native target/generation");
    REQUIRE(integer(s,"uid",0,1999999999) && eh_package(eh_string(s,"packageName")), "invalid Native UID/package");
    const char *exe = eh_string(s,"executable"), *process = eh_string(s,"processName");
    REQUIRE(exe && exe[0] == '/' && strlen(exe) < 1024 && !strpbrk(exe," \t\r\n"), "invalid Native executable");
    REQUIRE(process && strlen(process) < 256, "invalid Native process name");
    const cJSON *plugins = cJSON_GetObjectItemCaseSensitive(s,"plugins"), *p;
    REQUIRE(cJSON_IsArray(plugins) && cJSON_GetArraySize(plugins) <= EH_MAX_PLUGINS - 3, "Native entry supports at most 13 plugins");
    cJSON_ArrayForEach(p,plugins) {
        REQUIRE(eh_identifier(eh_string(p,"localPluginId")) && eh_identifier(eh_string(p,"buildId")) && eh_identifier(eh_string(p,"instanceId")), "invalid Native plugin identity");
        REQUIRE(integer(p,"sourceRevision",1,9007199254740991.0) && cJSON_IsObject(cJSON_GetObjectItemCaseSensitive(p,"config")), "invalid Native plugin configuration");
        const cJSON *before;
        for (before = plugins->child; before != p; before = before->next) REQUIRE(strcmp(eh_string(before,"localPluginId"),eh_string(p,"localPluginId")), "duplicate Native plugin");
    }
    return 0;
}
int eh_matches(const cJSON *p, const char *package_name, const char *process) {
    const char *scope = eh_string(p, "processScope");
    size_t length = strlen(package_name);
    int main = !strcmp(package_name, process), child = !strncmp(package_name, process, length) && process[length] == ':' && process[length + 1];
    if (!strcmp(scope, "exact")) return !strcmp(eh_string(p, "processName"), process);
    return (!strcmp(scope, "main") && main) || (!strcmp(scope, "children") && child) || (!strcmp(scope, "all") && (main || child));
}
int eh_read_all(int fd, void *buffer, size_t size) {
    char *p = buffer;
    while (size) { ssize_t n = read(fd, p, size); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; size -= (size_t)n; }
    return 0;
}
int eh_write_all(int fd, const void *buffer, size_t size) {
    const char *p = buffer;
    while (size) { ssize_t n = send(fd, p, size, MSG_NOSIGNAL); if (n < 0 && errno == EINTR) continue; if (n <= 0) return -1; p += n; size -= (size_t)n; }
    return 0;
}
void eh_deadline(int fd) {
    struct timeval timeout = {.tv_sec = EH_TIMEOUT_MS / 1000, .tv_usec = (EH_TIMEOUT_MS % 1000) * 1000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}
int eh_send(int fd, const struct eh_reply *r, const char *json, const int *fds) {
    char control[CMSG_SPACE(sizeof(int) * EH_MAX_PLUGINS)] = {0};
    struct iovec iov = {.iov_base = (void *)r, .iov_len = sizeof(*r)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1};
    if (r->fd_count) {
        msg.msg_control = control; msg.msg_controllen = CMSG_SPACE(sizeof(int) * r->fd_count);
        struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c->cmsg_level = SOL_SOCKET; c->cmsg_type = SCM_RIGHTS; c->cmsg_len = CMSG_LEN(sizeof(int) * r->fd_count);
        memcpy(CMSG_DATA(c), fds, sizeof(int) * r->fd_count);
    }
    ssize_t sent = sendmsg(fd, &msg, MSG_NOSIGNAL);
    if (sent <= 0) return -1;
    if ((size_t)sent < sizeof(*r) && eh_write_all(fd, (const char *)r + sent, sizeof(*r) - (size_t)sent)) return -1;
    return r->length ? eh_write_all(fd, json, r->length) : 0;
}
int eh_receive(int fd, struct eh_reply *r, char *json, int *fds) {
    char control[CMSG_SPACE(sizeof(int) * EH_MAX_PLUGINS)] = {0};
    struct iovec iov = {.iov_base = r, .iov_len = sizeof(*r)};
    struct msghdr msg = {.msg_iov = &iov, .msg_iovlen = 1, .msg_control = control, .msg_controllen = sizeof(control)};
    ssize_t got = recvmsg(fd, &msg, MSG_CMSG_CLOEXEC | MSG_WAITALL);
    unsigned count = 0;
    for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
        if (c->cmsg_level != SOL_SOCKET || c->cmsg_type != SCM_RIGHTS || c->cmsg_len < CMSG_LEN(0)) continue;
        size_t n = (c->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        if (count + n <= EH_MAX_PLUGINS) { memcpy(fds + count, CMSG_DATA(c), n * sizeof(int)); count += (unsigned)n; }
    }
    if (got != sizeof(*r) || (msg.msg_flags & (MSG_CTRUNC | MSG_TRUNC)) || r->protocol != EH_PROTOCOL || r->length >= EH_MAX_JSON || count != r->fd_count || r->result) goto fail;
    if (eh_read_all(fd, json, r->length)) goto fail;
    json[r->length] = 0; return 0;
fail:
    for (unsigned i = 0; i < count; i++) close(fds[i]);
    return -1;
}
cJSON *eh_read_snapshot(const char *path) {
    FILE *f = fopen(path, "re"); if (!f) return NULL;
    char *buffer = malloc(EH_MAX_JSON); if (!buffer) { fclose(f); return NULL; }
    size_t size = fread(buffer, 1, EH_MAX_JSON, f);
    int error = ferror(f); fclose(f);
    cJSON *json = NULL;
    if (!error && size < EH_MAX_JSON) {
        buffer[size] = 0;
        json = cJSON_ParseWithLengthOpts(buffer, size + 1, NULL, 1);
    }
    free(buffer); return json;
}
