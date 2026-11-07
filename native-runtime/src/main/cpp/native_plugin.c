#define _GNU_SOURCE
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/time.h>
#include <sys/mman.h>
#include "shadowhook.h"
#include "include/easylsp_native_plugin.h"
#include "include/easylsp_native_core.h"

#define MAX_INSTANCES 16
#define MAX_HOOKS 32
#define EVENT_COUNT 128
#define EVENT_BYTES 1536
extern int sh_engine_initialize(void);
struct plugin;
struct selector { char name[256], path[512], build_id[129]; };
struct hook {
    struct plugin *owner;
    sh_hook_id id;
    struct selector selector;
    char symbol[256];
    uintptr_t vaddr, address, load_bias;
    uint64_t load_id;
    uint32_t phase, mode, intercept_flags;
    sh_interceptor_v1 interceptor;
    void *interceptor_user;
    void *jni_class;
    char jni_name[128], jni_signature[256], jni_class_name[256];
    uint32_t jni_scope;
    void *replacement, **original, *stub;
    _Atomic uint64_t hits;
    _Atomic int state;
    int cancelled, plt_protection;
};
struct subscription {
    struct selector selector;
    uint32_t phases, id;
    sh_module_callback_v1 callback;
    void *user;
};
struct plugin {
    sh_plugin_context_v1 context;
    char plugin_id[64], build_id[64], instance_id[64], package_name[256], process_name[256], generation[64], release_id[129], config[8193];
    void *library;
    _Atomic int registering;
    _Atomic int active;
    _Atomic unsigned hook_count, subscription_count;
    struct hook hooks[MAX_HOOKS];
    struct subscription subscriptions[16];
};
static const sh_host_v1 host;
static _Thread_local int module_callback_depth, runtime_registration;
static _Thread_local struct plugin *registration_owner;
static const char *core_backend;
static struct plugin *instances[MAX_INSTANCES];
static _Atomic unsigned instance_count;
static pthread_mutex_t events_lock = PTHREAD_MUTEX_INITIALIZER;
static char events[EVENT_COUNT][EVENT_BYTES];
static unsigned event_end, event_size;
static uint64_t event_committed, event_positions[EVENT_COUNT];
static _Atomic uint64_t sequence, dropped;
#ifdef EASYLSP_CORE_PROFILE
static uint64_t profile_format_ns, profile_log_ns, profile_events, profile_build_reads, profile_selector_scans, profile_symbol_reads;
static uint64_t profile_clock(void) { struct timespec value; clock_gettime(CLOCK_MONOTONIC, &value); return (uint64_t)value.tv_sec * 1000000000 + value.tv_nsec; }
#define PROFILE_COUNT(name) (++profile_##name)
#else
#define PROFILE_COUNT(name) ((void)0)
#endif

static void escape(char *out, size_t capacity, const char *in) {
    size_t n = 0;
    while (*in && n + 2 < capacity) {
        unsigned char c = (unsigned char)*in++;
        if (c == '"' || c == '\\') { out[n++] = '\\'; out[n++] = (char)c; }
        else out[n++] = c < 32 || c >= 127 ? '?' : (char)c;
    }
    out[n] = 0;
}
static void event(struct plugin *p, sh_hook_id hook, const char *phase, int code, const char *detail) {
    int saved_errno = errno;
#ifdef EASYLSP_CORE_PROFILE
    uint64_t profile_start = profile_clock();
#endif
    char message[513], release[257], line[EVENT_BYTES];
    escape(release, sizeof(release), p->release_id);
    escape(message, sizeof(message), detail);
    uint64_t seq = atomic_fetch_add(&sequence, 1) + 1;
    struct timespec now; clock_gettime(CLOCK_REALTIME, &now);
    uint64_t timestamp = (uint64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
    snprintf(line, sizeof(line), "{\"schema\":1,\"uid\":%u,\"pid\":%u,\"startTicks\":%" PRIu64
        ",\"instanceId\":\"%s\",\"pluginId\":\"%s\",\"buildId\":\"%s\",\"generation\":\"%s\","
        "\"backend\":\"%s\",\"coreVersion\":\"0.9.1-engine2.0.1-m7\",\"releaseId\":\"%s\",\"timestamp\":%" PRIu64 ",\"sourceRevision\":%" PRIu64 ",\"sequence\":%" PRIu64 ",\"dropped\":%" PRIu64
        ",\"hookId\":%u,\"phase\":\"%s\",\"code\":%d,\"detail\":\"%s\"}",
        p->context.uid, p->context.pid, p->context.start_ticks, p->instance_id, p->plugin_id, p->build_id,
        p->generation, core_backend ? core_backend : "UNCLAIMED", release, timestamp, p->context.source_revision, seq, atomic_load(&dropped), hook, phase, code, message);
    if (pthread_mutex_trylock(&events_lock) == 0) {
        event_positions[event_end] = ++event_committed;
        memcpy(events[event_end], line, strlen(line) + 1);
        event_end = (event_end + 1) % EVENT_COUNT;
        if (event_size < EVENT_COUNT) event_size++; else atomic_fetch_add(&dropped, 1);
        pthread_mutex_unlock(&events_lock);
    } else atomic_fetch_add(&dropped, 1);
#ifdef EASYLSP_CORE_PROFILE
    uint64_t profile_log_start = profile_clock(); profile_format_ns += profile_log_start - profile_start;
#endif
    __android_log_write(ANDROID_LOG_INFO, "EasylspNative", line);
#ifdef EASYLSP_CORE_PROFILE
    profile_log_ns += profile_clock() - profile_log_start; profile_events++;
#endif
    errno = saved_errno;
}
static uint64_t start_ticks(void) {
    char line[4096];
    FILE *file = fopen("/proc/self/stat", "r");
    if (!file) return 0;
    char *read = fgets(line, sizeof(line), file);
    fclose(file);
    if (!read) return 0;
    char *cursor = strrchr(line, ')');
    if (!cursor) return 0;
    cursor += 2;
    for (int field = 3; field < 22; field++) {
        cursor = strchr(cursor, ' ');
        if (!cursor) return 0;
        cursor++;
    }
    return strtoull(cursor, NULL, 10);
}
#include "native_hooks.inc"
static void plugin_log(void *instance, const char *message) { event(instance, 0, "plugin_log", 0, message); }
static void hit(void *instance, sh_hook_id id) {
    int saved_errno = errno;
    struct plugin *p = instance;
    if (id && id <= p->hook_count && atomic_fetch_add(&p->hooks[id - 1].hits, 1) == 0)
        event(p, id, "hook_hit", 0, "first hit; further hits counted in process");
    errno = saved_errno;
}
#include "native_jni_hooks.inc"
static const sh_host_v1 host = { EASYLSP_NATIVE_ABI_V1, sizeof(sh_host_v1), hook_symbol, plugin_log, hit, hook_symbol_selected, hook_elf_vaddr, subscribe_module,
    hook_symbol_options, hook_elf_vaddr_options, intercept_elf_vaddr, hook_jni_method, (void *(*)(void))current_jni_env, hook_plt, register_tasks, remove_hook, unsubscribe_module };
static int copy_string(const char *source, char *destination, size_t capacity, int identifier) {
    if (!source || strlen(source) >= capacity) return SH_INVALID;
    if (identifier) {
        if (!*source) return SH_INVALID;
        for (const char *c = source; *c; c++)
            if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '-' || *c == '_')) return SH_INVALID;
    }
    strcpy(destination, source);
    return SH_OK;
}
static int core_process_claim = -1;
static pthread_mutex_t load_lock = PTHREAD_MUTEX_INITIALIZER;
int sh_core_claim(const char *backend) {
    if (!backend || (strcmp(backend, "ZYGISK") && strcmp(backend, "LIBXPOSED") && strcmp(backend, "NATIVE"))) return SH_INVALID;
    pthread_mutex_lock(&load_lock);
    int result = core_backend && strcmp(core_backend, backend) ? SH_CONFLICT : SH_OK;
    if (!result && !core_backend) {
        struct sockaddr_un address = {.sun_family = AF_UNIX};
        int length = snprintf(address.sun_path + 1, sizeof(address.sun_path) - 1,
            "easylsp-core.%u.%u", getuid(), getpid());
        int fd = socket(AF_UNIX, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0 || bind(fd, (struct sockaddr *)&address,
            offsetof(struct sockaddr_un, sun_path) + 1 + (size_t)length)) {
            if (fd >= 0) close(fd);
            result = SH_CONFLICT;
        } else {
            core_process_claim = fd; /* Kernel ownership crosses linker namespaces. */
            core_backend = !strcmp(backend, "ZYGISK") ? "ZYGISK" : !strcmp(backend, "LIBXPOSED") ? "LIBXPOSED" : "NATIVE";
        }
    }
    pthread_mutex_unlock(&load_lock);
    return result;
}
static int load_plugin(const sh_core_plugin_v1 *request) {
    int result = sh_engine_initialize();
    if (result) return result;
    pthread_once(&hooks_once, initialize_hooks);
    if (hooks_result) return hooks_result;
    if (instance_count == MAX_INSTANCES) return SH_LIMIT;
    struct plugin *p = calloc(1, sizeof(*p));
    if (!p) return SH_LIMIT;
    if (copy_string(request->plugin_id, p->plugin_id, sizeof(p->plugin_id), 1) ||
        copy_string(request->build_id, p->build_id, sizeof(p->build_id), 1) ||
        copy_string(request->instance_id, p->instance_id, sizeof(p->instance_id), 1) ||
        copy_string(request->package_name, p->package_name, sizeof(p->package_name), 0) ||
        copy_string(request->process_name, p->process_name, sizeof(p->process_name), 0) ||
        copy_string(request->generation, p->generation, sizeof(p->generation), 1) ||
        copy_string(request->release_id ? request->release_id : "", p->release_id, sizeof(p->release_id), 0) ||
        copy_string(request->config_json, p->config, sizeof(p->config), 0)) { free(p); return SH_INVALID; }
    for (unsigned i = 0; i < instance_count; i++) if (!strcmp(instances[i]->plugin_id, p->plugin_id)) { free(p); return SH_INVALID; }
    p->context = (sh_plugin_context_v1){ EASYLSP_NATIVE_ABI_V1, sizeof(sh_plugin_context_v1),
        p->plugin_id, p->build_id, p->instance_id, p->package_name, p->process_name, p->generation,
        request->source_revision, start_ticks(), getuid(), getpid(), p->config, p };
    event(p, 0, "plugin_loading", 0, "remote FD; RTLD_LOCAL");
    android_dlextinfo extension = { .flags = ANDROID_DLEXT_USE_LIBRARY_FD, .library_fd = request->fd };
    char name[160]; snprintf(name, sizeof(name), "easylsp-%s-%s.so", p->plugin_id, p->build_id);
    p->library = android_dlopen_ext(name, RTLD_NOW | RTLD_LOCAL, &extension);
    if (!p->library) {
        event(p, 0, "plugin_load_failed", SH_LOAD_FAILED, dlerror());
        free(p); return SH_LOAD_FAILED;
    }
    sh_plugin_init_v1 init = (sh_plugin_init_v1)dlsym(p->library, "easylsp_plugin_init_v1");
    if (!init) {
        event(p, 0, "plugin_load_failed", SH_ENTRY_MISSING, dlerror());
        /* Constructors may have started work; retain the mapping, but no context
         * or callbacks have been published, so the instance can be discarded. */
        free(p); return SH_ENTRY_MISSING;
    }
    atomic_store(&p->active, 1);
    instances[instance_count] = p;
    atomic_fetch_add(&instance_count, 1);
    p->registering = 1; registration_owner = p;
    result = init(&host, &p->context);
    registration_owner = NULL; p->registering = 0;
    if (result) dl_iterate_phdr(cleanup_failed_instance, p);
    /* Keep the library and callback/original-pointer storage mapped even after failed init. */
    event(p, 0, result == 0 ? "plugin_initialized" : "plugin_init_failed", result, "");
    return result;
}
int sh_core_load_plugin_fd(const sh_core_plugin_v1 *request) {
    if (!request || request->struct_size < sizeof(*request) || request->fd < 0) return SH_INVALID;
    pthread_mutex_lock(&load_lock);
    int result = load_plugin(request);
    pthread_mutex_unlock(&load_lock);
    return result;
}
void sh_core_read_events_since(uint64_t *cursor, sh_core_event_callback callback, void *user) {
    /* Commit positions, unlike event sequence numbers, cannot arrive out of order. */
    char snapshot[16][EVENT_BYTES];
    pthread_mutex_lock(&events_lock);
    uint64_t end = event_committed;
    pthread_mutex_unlock(&events_lock);
    while (*cursor < end) {
        unsigned count = 0;
        pthread_mutex_lock(&events_lock);
        for (unsigned i = 0; i < event_size && count < 16; i++) {
            unsigned slot = (event_end + EVENT_COUNT - event_size + i) % EVENT_COUNT;
            if (event_positions[slot] > *cursor && event_positions[slot] <= end) {
                strcpy(snapshot[count++], events[slot]); *cursor = event_positions[slot];
            }
        }
        if (!count) *cursor = end;
        pthread_mutex_unlock(&events_lock);
        for (unsigned i = 0; i < count; i++) callback(user, snapshot[i]);
    }
}
void sh_core_read_events(sh_core_event_callback callback, void *user) {
    uint64_t cursor = 0; sh_core_read_events_since(&cursor, callback, user);
}

#include "native_event_server.inc"
