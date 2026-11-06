#define _GNU_SOURCE
#include "next_api.h"
#include "protocol.h"
#include "easylsp_native_core.h"
#include <android/dlext.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#ifndef EH_NEXT_API_VERSION
#define EH_NEXT_API_VERSION 3
#endif
static const struct eh_next_api *provider;
static void *self_handle;
static void *load_fd(int fd, const char *name, int flags) {
    android_dlextinfo extension = {.flags = ANDROID_DLEXT_USE_LIBRARY_FD, .library_fd = fd};
    return android_dlopen_ext(name, RTLD_NOW | flags, &extension);
}
static void load_target(const char *process, const char *package) {
    int socket = provider->connect_companion(self_handle);
    if (socket < 0) return;
    eh_deadline(socket);
    struct { struct eh_query identity; uint32_t pid; } message = {.identity = {.protocol = EH_PROTOCOL, .uid = getuid()}, .pid = getpid()};
    struct eh_query *query = &message.identity;
    if (strlen(process) >= sizeof(query->process) || strlen(package) >= sizeof(query->app_data_dir)) { close(socket); return; }
    strcpy(query->process, process); strcpy(query->app_data_dir, package);
    char *json = malloc(EH_MAX_JSON); struct eh_reply reply = {0}; int fds[EH_MAX_PLUGINS];
    int result = json ? eh_write_all(socket, &message, sizeof(message)) : -1;
    if (!result) result = eh_receive(socket, &reply, json, fds);
    close(socket);
    if (result || !reply.length) { free(json); return; }
    cJSON *config = cJSON_Parse(json); free(json);
    cJSON *plugins = cJSON_GetObjectItemCaseSensitive(config, "plugins");
    if (reply.fd_count < 3 || reply.fd_count != (unsigned)cJSON_GetArraySize(plugins) + 3) goto done;
    void *backend = load_fd(fds[0], "libshadowhook.so", RTLD_GLOBAL);
    void *core = backend ? load_fd(fds[2], "libeasylsp_native.so", RTLD_LOCAL) : NULL;
    if (!core) goto done;
    int (*initialize)(int) = dlsym(backend, "easylsp_shadowhook_init_with_fd");
    int (*claim)(const char *) = dlsym(core, "sh_core_claim");
    int (*install)(const sh_core_plugin_v1 *) = dlsym(core, "sh_core_load_plugin_fd");
    if (!initialize || !claim || !install || initialize(fds[1]) || claim("NATIVE")) goto done;
    for (int i = 0; i < cJSON_GetArraySize(plugins); i++) {
        cJSON *p = cJSON_GetArrayItem(plugins, i);
        char *options = cJSON_PrintUnformatted(cJSON_GetObjectItemCaseSensitive(p, "config"));
        sh_core_plugin_v1 request = {sizeof(request), fds[i + 3], eh_string(p,"localPluginId"), eh_string(p,"buildId"), eh_string(p,"instanceId"),
            eh_string(config,"packageName"), process, eh_string(config,"generation"), (uint64_t)cJSON_GetObjectItemCaseSensitive(p,"sourceRevision")->valuedouble,
            options, eh_string(p,"releaseId")};
        int code = install(&request); free(options);
        __android_log_print(code ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,"EasylspNext","api=%d process=%s plugin=%s result=%d", EH_NEXT_API_VERSION, process, request.plugin_id, code);
    }
done:
    for (unsigned i = 0; i < reply.fd_count; i++) close(fds[i]);
    cJSON_Delete(config);
}
#if EH_NEXT_API_VERSION >= 4
static void specialized(const struct eh_hyos_args *args) { load_target(args->process_name, args->package_name); }
#endif
static void loaded(void *handle, const struct eh_next_api *api) {
    self_handle = handle; provider = api;
#if EH_NEXT_API_VERSION >= 4
    const struct eh_next_runtime *runtime = api->get_runtime();
    if (runtime && runtime->type == 1 && runtime->api_version >= 1) {
        const struct eh_hyos_module module = {1, specialized};
        int result = runtime->register_module(&module);
        __android_log_print(result ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO,"EasylspNext","runtime=hyos registration=%d", result);
        return;
    }
#endif
    char process[256]; FILE *cmd = fopen("/proc/self/cmdline", "re");
    size_t n = cmd ? fread(process, 1, sizeof(process) - 1, cmd) : 0; if (cmd) fclose(cmd);
    if (n) { process[n] = 0; load_target(process, ""); }
}
static void connected(int socket) {
    eh_deadline(socket); struct { struct eh_query identity; uint32_t pid; } message = {0};
    struct eh_query *query = &message.identity; struct eh_reply reply = {.protocol = EH_PROTOCOL};
    struct ucred peer; socklen_t peer_size = sizeof(peer);
    int fds[EH_MAX_PLUGINS], count = 0; cJSON *config = NULL; char *json = NULL;
    if (getsockopt(socket, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) || eh_read_all(socket, &message, sizeof(message)) || query->protocol != EH_PROTOCOL || (peer.uid != 0 && (query->uid != (uint32_t)peer.uid || message.pid != (uint32_t)peer.pid)) ||
        !memchr(query->process, 0, sizeof(query->process)) || !memchr(query->app_data_dir, 0, sizeof(query->app_data_dir))) goto done;
    char proc[80], executable[1024]; snprintf(proc, sizeof(proc), "/proc/%u/exe", message.pid);
    ssize_t n = readlink(proc, executable, sizeof(executable) - 1); if (n < 0) goto done; executable[n] = 0;
    snprintf(proc, sizeof(proc), "/proc/%u", message.pid);
    struct stat identity; if (stat(proc,&identity) || identity.st_uid != query->uid) goto done;
    DIR *directory = opendir(EH_DATA "/native-targets"); if (!directory) goto done;
    struct dirent *entry;
    while ((entry = readdir(directory))) {
        size_t length = strlen(entry->d_name); if (length < 6 || strcmp(entry->d_name + length - 5, ".json")) continue;
        char path[1400]; snprintf(path, sizeof(path), EH_DATA "/native-targets/%s", entry->d_name);
        cJSON *candidate = eh_read_snapshot(path); char error[256];
        if (eh_validate_native(candidate, error, sizeof(error)) || strcmp(eh_string(candidate,"executable"), executable) || cJSON_GetObjectItemCaseSensitive(candidate,"uid")->valuedouble != query->uid ||
            (eh_string(candidate,"processName")[0] && strcmp(eh_string(candidate,"processName"),query->process)) ||
            (query->app_data_dir[0] && strcmp(eh_string(candidate,"packageName"),query->app_data_dir))) { cJSON_Delete(candidate); continue; }
        if (config) { cJSON_Delete(candidate); cJSON_Delete(config); config = NULL; break; } /* Two matches never pick one silently. */
        config = candidate;
    }
    closedir(directory);
    if (!config) goto done;
    const char *runtime[] = {"libshadowhook.so", "libshadowhook_nothing.so", "libeasylsp_native.so"};
    for (int i = 0; i < 3; i++) {
        char path[1400]; snprintf(path,sizeof(path),"/data/adb/modules/" EH_MODULE "/runtime/" EH_VERSION "/arm64-v8a/%s",runtime[i]);
        int fd = open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW); if (fd < 0) goto done; fds[count++] = fd;
    }
    cJSON *p; cJSON_ArrayForEach(p,cJSON_GetObjectItemCaseSensitive(config,"plugins")) {
        char path[1400]; snprintf(path,sizeof(path),EH_DATA "/artifacts/%s/%s/plugin.so",eh_string(p,"localPluginId"),eh_string(p,"buildId"));
        int fd = open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW); if (fd < 0) goto done; fds[count++] = fd;
    }
    json = cJSON_PrintUnformatted(config); if (!json || strlen(json) >= EH_MAX_JSON) goto done;
    reply.fd_count = (unsigned)count; reply.length = (unsigned)strlen(json);
done:
    eh_send(socket,&reply,json,fds); for (int i = 0; i < count; i++) close(fds[i]);
    free(json); cJSON_Delete(config); close(socket);
}
__attribute__((visibility("default"))) struct eh_next_module zn_module = {EH_NEXT_API_VERSION,loaded};
__attribute__((visibility("default"))) struct eh_next_companion zn_companion_module = {EH_NEXT_API_VERSION,NULL,connected};
