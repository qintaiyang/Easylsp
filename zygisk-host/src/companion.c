#define _GNU_SOURCE
#include "protocol.h"
#include "companion.h"
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>
#include <time.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

static void record_runtime(const char *process, unsigned long long elapsed_us) {
    static int recorded;
    if (recorded) return;
    char boot[64], temporary[256]; if (eh_boot_id(boot)) return;
    if (mkdir(EH_DATA, 0700) && errno != EEXIST) return;
    snprintf(temporary, sizeof(temporary), EH_DATA "/runtime.%u.tmp", getpid());
    FILE *file = fopen(temporary, "we"); if (!file) return;
    fprintf(file, "{\"runtimeVersion\":\"%s\",\"bootId\":\"%s\",\"companionPid\":%u,\"firstQueryProcess\":\"%s\",\"firstQueryUs\":%llu}", EH_RUNTIME, boot, getpid(), process, elapsed_us);
    if (fclose(file) || rename(temporary, EH_DATA "/runtime.json")) return;
    chmod(EH_DATA "/runtime.json", 0600); recorded = 1;
}
void eh_companion(int socket) {
    struct timespec started; clock_gettime(CLOCK_MONOTONIC, &started);
    eh_deadline(socket);
    struct eh_reply reply = {.protocol = EH_PROTOCOL};
    struct eh_query query = {0};
    struct ucred peer = {0}; socklen_t size = sizeof(peer);
    int fds[EH_MAX_PLUGINS]; unsigned count = 0;
    cJSON *snapshot = NULL;
    char *json = NULL;
    if (getsockopt(socket, SOL_SOCKET, SO_PEERCRED, &peer, &size) || peer.uid != 0 || eh_read_all(socket, &query, sizeof(query))) { reply.result = -EACCES; goto done; }
    if (query.protocol != EH_PROTOCOL || !memchr(query.process, 0, sizeof(query.process)) || !memchr(query.app_data_dir, 0, sizeof(query.app_data_dir)) ||
        ((query.uid % 100000 < 10000 || query.uid % 100000 >= 20000) && !(query.uid % 100000 >= 90000 && query.uid % 100000 <= 99999))) { reply.result = -EINVAL; goto done; }
    int isolated = query.uid % 100000 >= 90000;
    char selected_package[256]; const char *package_name = NULL;
    char path[1200];
    if (isolated) {
        char users[256]; snprintf(users,sizeof(users),EH_DATA "/deployments");
        DIR *directory = opendir(users); if (!directory) goto done;
        struct dirent *user;
        while ((user = readdir(directory))) {
            char *end; unsigned long uid = strtoul(user->d_name,&end,10);
            if (*end || uid / 100000 != query.uid / 100000 || uid % 100000 < 10000 || uid % 100000 >= 20000) continue;
            char folder[512]; snprintf(folder,sizeof(folder),EH_DATA "/deployments/%lu",uid);
            DIR *packages = opendir(folder); if (!packages) continue; struct dirent *entry;
            while ((entry = readdir(packages))) {
                size_t n = strlen(entry->d_name); if (n < 6 || strcmp(entry->d_name+n-5,".json")) continue;
                snprintf(path,sizeof(path),"%s/%s",folder,entry->d_name);
                cJSON *candidate = eh_read_snapshot(path); char error[256];
                if (eh_validate(candidate,error,sizeof(error))) { cJSON_Delete(candidate); continue; }
                int match = 0; const cJSON *p;
                cJSON_ArrayForEach(p,cJSON_GetObjectItemCaseSensitive(candidate,"orderedPlugins")) {
                    const char *type = eh_string(p,"processType");
                    if (type && strcmp(type,"app") && !strcmp(eh_string(p,"processScope"),"exact") && !strcmp(eh_string(p,"processName"),query.process)) match = 1;
                }
                if (!match) { cJSON_Delete(candidate); continue; }
                if (snapshot) { cJSON_Delete(candidate); closedir(packages); closedir(directory); reply.result = -EEXIST; goto done; }
                snapshot = candidate;
            }
            closedir(packages);
        }
        closedir(directory);
        if (!snapshot) goto done;
        strcpy(selected_package,eh_string(snapshot,"packageName")); package_name = selected_package;
    } else {
        package_name = strrchr(query.app_data_dir, '/');
        if (!package_name || !eh_package(++package_name)) goto done;
        char expected[768], legacy[768];
        snprintf(expected, sizeof(expected), "/data/user/%u/%s", query.uid / 100000, package_name);
        snprintf(legacy, sizeof(legacy), "/data/user_de/%u/%s", query.uid / 100000, package_name);
        if (strcmp(expected, query.app_data_dir) && strcmp(legacy, query.app_data_dir)) {
            snprintf(legacy, sizeof(legacy), "/data/data/%s", package_name);
            if (query.uid / 100000 || strcmp(legacy, query.app_data_dir)) goto done;
        }
    }
    snprintf(path, sizeof(path), "%s/deployments/%u/%s.json", EH_DATA, query.uid, package_name);
    char diagnostic_path[1200]; snprintf(diagnostic_path, sizeof(diagnostic_path), "%s/diagnostics/%u/%s.json", EH_DATA, query.uid, package_name);
    cJSON *override = eh_read_snapshot(diagnostic_path);
    cJSON *diagnostic = cJSON_GetObjectItemCaseSensitive(override, "diagnostic");
    const char *diagnostic_process = eh_string(diagnostic, "processName");
    const cJSON *expires = cJSON_GetObjectItemCaseSensitive(diagnostic, "expiresAt");
    char diagnostic_error[256];
    struct timespec elapsed; clock_gettime(CLOCK_BOOTTIME, &elapsed);
    const cJSON *elapsed_deadline = cJSON_GetObjectItemCaseSensitive(diagnostic, "expiresElapsed");
    char boot[64]; const char *diagnostic_boot = eh_string(override, "bootId");
    if (!isolated && override && diagnostic_boot && !eh_boot_id(boot) && !strcmp(boot, diagnostic_boot) && cJSON_IsNumber(elapsed_deadline) && elapsed_deadline->valuedouble > (double)elapsed.tv_sec * 1000 + elapsed.tv_nsec / 1000000 && !eh_validate(override, diagnostic_error, sizeof(diagnostic_error)) && expires->valuedouble > (double)time(NULL) * 1000 &&
        (!strcmp(diagnostic_process, "*") || !strcmp(diagnostic_process, query.process))) {
        if (strcmp(eh_string(diagnostic, "mode"), "NORMAL")) { snapshot = override; override = NULL; }
        else {
            snapshot = eh_read_snapshot(path);
            if (snapshot) cJSON_AddItemToObject(snapshot, "diagnostic", cJSON_Duplicate(diagnostic, 1));
            else { snapshot = override; override = NULL; }
        }
    }
    cJSON_Delete(override);
    if (!snapshot) snapshot = eh_read_snapshot(path);
    if (!snapshot) goto done;
    char error[256];
    if (eh_validate(snapshot, error, sizeof(error))) { reply.result = -EPROTO; goto done; }
    if ((!isolated && cJSON_GetObjectItemCaseSensitive(snapshot, "uid")->valuedouble != query.uid) || strcmp(eh_string(snapshot, "packageName"), package_name)) { reply.result = -EACCES; goto done; }
    struct stat install;
    if (stat(eh_string(snapshot, "targetInstallIdentity"), &install)) { reply.result = -ESTALE; goto done; }
    cJSON *plugins = cJSON_GetObjectItemCaseSensitive(snapshot, "orderedPlugins");
    for (int i = 0; i < cJSON_GetArraySize(plugins);) {
        cJSON *p = cJSON_GetArrayItem(plugins, i);
        const char *type = eh_string(p,"processType"); int special = type && strcmp(type,"app");
        if ((isolated != !!special) || !eh_matches(p, package_name, query.process)) { cJSON_DeleteItemFromArray(plugins, i); continue; }
        snprintf(path, sizeof(path), "%s/artifacts/%s/%s/plugin.so", EH_DATA, eh_string(p, "localPluginId"), eh_string(p, "buildId"));
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) { reply.result = -errno; goto done; }
        fds[count++] = fd; i++;
    }
    if (!count && !cJSON_GetObjectItemCaseSensitive(snapshot, "diagnostic")) goto done;
    json = cJSON_PrintUnformatted(snapshot);
    if (!json || strlen(json) >= EH_MAX_JSON) { reply.result = -E2BIG; goto done; }
    reply.length = (uint32_t)strlen(json); reply.fd_count = count;
done:
#ifdef __ANDROID__
    if (reply.result) __android_log_print(ANDROID_LOG_WARN, "EasylspHost", "phase=companion_failed code=%d peerUid=%u errno=%d", reply.result, peer.uid, errno);
#endif
    if (reply.result) { reply.length = 0; reply.fd_count = 0; }
    struct timespec finished; clock_gettime(CLOCK_MONOTONIC, &finished);
    unsigned long long elapsed_us = (unsigned long long)((finished.tv_sec - started.tv_sec) * 1000000LL + (finished.tv_nsec - started.tv_nsec) / 1000);
    record_runtime(memchr(query.process, 0, sizeof(query.process)) && eh_package(query.process) ? query.process : "unknown", elapsed_us);
    eh_send(socket, &reply, json, fds);
    for (unsigned i = 0; i < count; i++) close(fds[i]);
    free(json); cJSON_Delete(snapshot);
}
