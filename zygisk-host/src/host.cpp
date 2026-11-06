#include <sys/types.h>
#include "zygisk.hpp"
#include "protocol.h"
#include "companion.h"
#include "easylsp_native_core.h"
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/system_properties.h>

static void evidence(const char *phase, int code, const char *detail) {
    __android_log_print(ANDROID_LOG_INFO, "EasylspHost", "hostVersion=%s backend=ZYGISK pid=%u uid=%u phase=%s code=%d %s", EH_VERSION, getpid(), getuid(), phase, code, detail ? detail : "");
}
static uint64_t monotonic_us() {
    timespec value; clock_gettime(CLOCK_MONOTONIC, &value);
    return static_cast<uint64_t>(value.tv_sec) * 1000000 + value.tv_nsec / 1000;
}
struct PhaseTimer {
    const char *phase; bool enabled; uint64_t started;
    PhaseTimer(const char *value, bool active) : phase(value), enabled(active), started(active ? monotonic_us() : 0) {}
    ~PhaseTimer() { if (enabled) __android_log_print(ANDROID_LOG_INFO, "EasylspHostPerf", "pid=%u phase=%s elapsedUs=%llu", getpid(), phase, (unsigned long long)(monotonic_us() - started)); }
};
class EasylspHost : public zygisk::ModuleBase {
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
    struct eh_query query{};
    struct eh_reply reply{};
    cJSON *snapshot = nullptr;
    int plugins[EH_MAX_PLUGINS];
    int shadow = -1, helper = -1, core = -1;
    bool measure = false;
    void close_fds() {
        for (unsigned i = 0; i < reply.fd_count; i++) close(plugins[i]);
        reply.fd_count = 0;
        if (shadow >= 0) { close(shadow); shadow = -1; }
        if (helper >= 0) { close(helper); helper = -1; }
        if (core >= 0) { close(core); core = -1; }
    }
    bool string(jstring value, char *out, size_t capacity) {
        if (!value || static_cast<size_t>(env->GetStringUTFLength(value)) >= capacity) return false;
        const char *text = env->GetStringUTFChars(value, nullptr);
        if (!text) return false;
        strcpy(out, text); env->ReleaseStringUTFChars(value, text); return true;
    }
    void skip() {
        close_fds(); cJSON_Delete(snapshot); snapshot = nullptr;
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }
    void *load(int fd, const char *name, int flags) {
        android_dlextinfo extension{};
        extension.flags = ANDROID_DLEXT_USE_LIBRARY_FD; extension.library_fd = fd;
        void *library = android_dlopen_ext(name, RTLD_NOW | flags, &extension);
        if (!library) evidence("runtime_load_failed", -1, dlerror());
        return library;
    }
public:
    void onLoad(zygisk::Api *value, JNIEnv *environment) override { api = value; env = environment; }
    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override { api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); }
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        query.protocol = EH_PROTOCOL; query.uid = static_cast<uint32_t>(args->uid);
        /* Keep the module available to provider-delivered descendants; never load
         * a target plugin into the app-zygote parent itself. */
        if (args->is_child_zygote && *args->is_child_zygote) { evidence("app_zygote_parent", 0, "target plugins deferred to specialized children"); return; }
        unsigned app_id = query.uid % 100000;
        bool isolated = app_id >= 90000 && app_id <= 99999;
        if ((!isolated && (app_id < 10000 || app_id >= 20000)) ||
            !string(args->nice_name, query.process, sizeof(query.process)) ||
            (!isolated && !string(args->app_data_dir, query.app_data_dir, sizeof(query.app_data_dir)))) { skip(); return; }
        if (isolated) query.app_data_dir[0] = 0;
        char performance_target[PROP_VALUE_MAX];
        __system_property_get("debug.easylsp.perf_target", performance_target);
        measure = !strcmp(performance_target, query.process);
        PhaseTimer timer("pre", measure);
        uint64_t ipc_start = measure ? monotonic_us() : 0;
        int socket = api->connectCompanion();
        if (socket < 0) { evidence("companion_unavailable", socket, query.process); skip(); return; }
        eh_deadline(socket);
        char *json = static_cast<char *>(malloc(EH_MAX_JSON));
        int result = json ? eh_write_all(socket, &query, sizeof(query)) : -1;
        if (!result) result = eh_receive(socket, &reply, json, plugins);
        int ipc_errno = errno;
        if (measure) __android_log_print(ANDROID_LOG_INFO, "EasylspHostPerf", "pid=%u phase=companion_query elapsedUs=%llu", getpid(), (unsigned long long)(monotonic_us() - ipc_start));
        close(socket);
        if (result) {
            char detail[512]; snprintf(detail, sizeof(detail), "%s errno=%d replyProtocol=%u replyLength=%u replyFds=%u", query.process, ipc_errno, reply.protocol, reply.length, reply.fd_count);
            reply.fd_count = 0; free(json); evidence("config_failed", reply.result ? reply.result : result, detail); skip(); return;
        }
        if (!reply.length) { free(json); skip(); return; }
        snapshot = cJSON_Parse(json); free(json);
        char error[256] = "plugin/FD count mismatch";
        if (eh_validate(snapshot, error, sizeof(error)) || cJSON_GetArraySize(cJSON_GetObjectItemCaseSensitive(snapshot, "orderedPlugins")) != static_cast<int>(reply.fd_count)) {
            evidence("config_failed", -1, error); skip(); return;
        }
        cJSON *diagnostic = cJSON_GetObjectItemCaseSensitive(snapshot, "diagnostic");
        if (diagnostic && strcmp(eh_string(diagnostic, "mode"), "NATIVE_OBSERVE") && (strcmp(eh_string(diagnostic, "mode"), "NORMAL") || !reply.fd_count)) return;
        int module = api->getModuleDir();
        if (module >= 0) {
            shadow = openat(module, "runtime/" EH_VERSION "/arm64-v8a/libshadowhook.so", O_RDONLY | O_CLOEXEC);
            helper = openat(module, "runtime/" EH_VERSION "/arm64-v8a/libshadowhook_nothing.so", O_RDONLY | O_CLOEXEC);
            core = openat(module, "runtime/" EH_VERSION "/arm64-v8a/libeasylsp_native.so", O_RDONLY | O_CLOEXEC);
            close(module);
        }
        if (shadow < 0 || helper < 0 || core < 0) { evidence("runtime_missing", -1, EH_VERSION); skip(); return; }
        /* Record API return separately from post-stage FD survival. Re/Neo need device evidence. */
        for (unsigned i = 0; i < reply.fd_count; i++) evidence("exempt_plugin_fd", api->exemptFd(plugins[i]), query.process);
        evidence("exempt_shadow_fd", api->exemptFd(shadow), query.process);
        evidence("exempt_helper_fd", api->exemptFd(helper), query.process);
        evidence("exempt_core_fd", api->exemptFd(core), query.process);
        evidence("pre_configured", 0, eh_string(snapshot, "generation"));
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        PhaseTimer timer("post", measure);
        if (!snapshot) return;
        evidence("post_entry", 0, eh_string(snapshot, "generation"));
        if (getuid() != query.uid) { evidence("identity_mismatch", -1, query.process); close_fds(); cJSON_Delete(snapshot); snapshot = nullptr; return; }
        cJSON *diagnostic = cJSON_GetObjectItemCaseSensitive(snapshot, "diagnostic");
        if (diagnostic) {
            __android_log_print(ANDROID_LOG_INFO, "EasylspDiag", "{\"schema\":1,\"diagnosticRunId\":\"%s\",\"mode\":\"%s\",\"phase\":\"module_loaded\",\"backend\":\"ZYGISK\",\"uid\":%u,\"pid\":%u}",
                eh_string(diagnostic, "diagnosticRunId"), eh_string(diagnostic, "mode"), getuid(), getpid());
            if (strcmp(eh_string(diagnostic, "mode"), "NATIVE_OBSERVE") && (strcmp(eh_string(diagnostic, "mode"), "NORMAL") || !reply.fd_count)) { close_fds(); cJSON_Delete(snapshot); snapshot = nullptr; return; }
        }
        if (fcntl(shadow, F_GETFD) < 0 || fcntl(helper, F_GETFD) < 0 || fcntl(core, F_GETFD) < 0) { evidence("fd_lost", -1, query.process); close_fds(); cJSON_Delete(snapshot); snapshot = nullptr; return; }
        void *backend = load(shadow, "libshadowhook.so", RTLD_GLOBAL);
        void *library = backend ? load(core, "libeasylsp_native.so", RTLD_LOCAL) : nullptr;
        if (library) {
            auto claim = reinterpret_cast<int (*)(const char *)>(dlsym(library, "sh_core_claim"));
            auto install = reinterpret_cast<int (*)(const sh_core_plugin_v1 *)>(dlsym(library, "sh_core_load_plugin_fd"));
            if (!claim || !install) evidence("core_protocol_missing", -1, EH_VERSION);
            else if (int result = claim("ZYGISK")) evidence("backend_conflict", result, query.process);
            else {
                auto initialize = reinterpret_cast<int (*)(int)>(dlsym(backend, "easylsp_shadowhook_init_with_fd"));
                auto jni = reinterpret_cast<int (*)(void *)>(dlsym(library, "sh_core_set_jni_env"));
                int engine = initialize ? initialize(helper) : -1;
                evidence("engine_initialized", engine, "shadowhook=2.0.1 helper=FD");
                if (engine) { close_fds(); cJSON_Delete(snapshot); snapshot = nullptr; return; }
                if (diagnostic && !strcmp(eh_string(diagnostic, "mode"), "NATIVE_OBSERVE")) {
                    auto observe = reinterpret_cast<int (*)(const char *)>(dlsym(library, "sh_core_start_observation"));
                    evidence("native_observation", observe ? observe(eh_string(diagnostic, "nativeSocket")) : -1, eh_string(diagnostic, "diagnosticRunId"));
                    close_fds(); cJSON_Delete(snapshot); snapshot = nullptr; return;
                }
                int jni_result = jni ? jni(env) : -1;
                evidence("jni_observer", jni_result, query.process);
                evidence("core_loaded", 0, EH_VERSION);
                auto event_server = reinterpret_cast<int (*)(void)>(dlsym(library, "sh_core_start_event_server"));
                evidence("event_server", event_server ? event_server() : -1, query.process);
                cJSON *list = cJSON_GetObjectItemCaseSensitive(snapshot, "orderedPlugins");
                for (unsigned i = 0; i < reply.fd_count; i++) {
                    cJSON *p = cJSON_GetArrayItem(list, static_cast<int>(i));
                    char instance[64]; snprintf(instance, sizeof(instance), "z-%u-%u", getpid(), i);
                    char *config = cJSON_PrintUnformatted(cJSON_GetObjectItemCaseSensitive(p, "config"));
                    sh_core_plugin_v1 request = {sizeof(request), plugins[i], eh_string(p, "localPluginId"), eh_string(p, "buildId"), instance,
                        eh_string(snapshot, "packageName"), query.process, eh_string(snapshot, "generation"),
                        static_cast<uint64_t>(cJSON_GetObjectItemCaseSensitive(p, "sourceRevision")->valuedouble), config, eh_string(p, "releaseId")};
                    uint64_t init_start = measure ? monotonic_us() : 0;
                    int result = install(&request); free(config);
                    if (measure) __android_log_print(ANDROID_LOG_INFO, "EasylspHostPerf", "pid=%u phase=plugin_init plugin=%s elapsedUs=%llu", getpid(), request.plugin_id, (unsigned long long)(monotonic_us() - init_start));
                    evidence("plugin_result", result, request.plugin_id);
                }
            }
        }
        /* Libraries remain mapped; no Zygisk API is used after this callback. */
        close_fds(); cJSON_Delete(snapshot); snapshot = nullptr;
    }
};
REGISTER_ZYGISK_MODULE(EasylspHost)
REGISTER_ZYGISK_COMPANION(eh_companion)
