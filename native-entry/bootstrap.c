#define _GNU_SOURCE
#include "easylsp_native_core.h"
#include "cJSON.h"
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern int easylsp_shadowhook_init_with_fd(int fd);

/* Explicit exec launcher only. No ART/JNI and no process-wide environment edits
 * outside this child. ELF constructors have already run; main has not. */
__attribute__((constructor)) static void bootstrap(void) {
    const char *setting = getenv("EASYLSP_NATIVE_CONFIG");
    if (!setting) return;
    char *path = strdup(setting); unsetenv("EASYLSP_NATIVE_CONFIG");
    const char *previous = getenv("EASYLSP_PREVIOUS_LD_PRELOAD");
    if (previous && *previous) setenv("LD_PRELOAD", previous, 1); else unsetenv("LD_PRELOAD");
    unsetenv("EASYLSP_PREVIOUS_LD_PRELOAD");
    FILE *file = path ? fopen(path, "re") : NULL; free(path);
    if (!file) { __android_log_print(ANDROID_LOG_ERROR, "EasylspNativeEntry", "config open failed"); _exit(125); }
    char *json = malloc(256 * 1024 + 1); size_t size = json ? fread(json, 1, 256 * 1024 + 1, file) : 0; fclose(file);
    if (!json || !size || size > 256 * 1024) { free(json); _exit(125); }
    json[size] = 0;
    cJSON *config = cJSON_ParseWithLengthOpts(json, size + 1, NULL, 1); free(json);
    const cJSON *schema = cJSON_GetObjectItemCaseSensitive(config, "schema");
    const char *package = cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(config, "packageName"));
    const char *generation = cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(config, "generation"));
    cJSON *plugins = cJSON_GetObjectItemCaseSensitive(config, "plugins");
    if (!schema || schema->valueint != 1 || !package || !generation || !cJSON_IsArray(plugins) || cJSON_GetArraySize(plugins) > 16 || sh_core_claim("NATIVE")) { cJSON_Delete(config); _exit(125); }
    Dl_info location;
    char helper[PATH_MAX];
    if (!dladdr((void *)bootstrap, &location)) _exit(125);
    const char *slash = strrchr(location.dli_fname, '/');
    if (!slash || snprintf(helper, sizeof(helper), "%.*s/libshadowhook_nothing.so", (int)(slash - location.dli_fname), location.dli_fname) >= (int)sizeof(helper)) _exit(125);
    int helper_fd = open(helper, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    int initialized = helper_fd < 0 ? -1 : easylsp_shadowhook_init_with_fd(helper_fd);
    if (helper_fd >= 0) close(helper_fd);
    if (initialized) { __android_log_print(ANDROID_LOG_ERROR, "EasylspNativeEntry", "engine init=%d", initialized); _exit(125); }
    char process[256]; ssize_t n = readlink("/proc/self/exe", process, sizeof(process) - 1);
    if (n < 0) { cJSON_Delete(config); _exit(125); } process[n] = 0;
    cJSON *plugin;
    cJSON_ArrayForEach(plugin, plugins) {
        const char *artifact = cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(plugin, "path"));
        int fd = artifact && artifact[0] == '/' ? open(artifact, O_RDONLY | O_CLOEXEC | O_NOFOLLOW) : -1;
        char *options = cJSON_PrintUnformatted(cJSON_GetObjectItemCaseSensitive(plugin, "config"));
        sh_core_plugin_v1 request = {sizeof(request), fd,
            cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(plugin, "pluginId")),
            cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(plugin, "buildId")),
            cJSON_GetStringValue(cJSON_GetObjectItemCaseSensitive(plugin, "instanceId")),
            package, process, generation, 1, options ? options : "{}", ""};
        int result = fd < 0 ? -1 : sh_core_load_plugin_fd(&request);
        if (fd >= 0) close(fd);
        free(options);
        __android_log_print(result ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO, "EasylspNativeEntry", "plugin=%s result=%d", request.plugin_id ? request.plugin_id : "?", result);
        if (result) { cJSON_Delete(config); _exit(125); }
    }
    if (cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(config, "events"))) {
        int result = sh_core_start_event_server();
        if (result) { cJSON_Delete(config); _exit(125); }
    }
    cJSON_Delete(config);
}
