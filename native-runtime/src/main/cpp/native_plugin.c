#define _GNU_SOURCE
#include <android/dlext.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <inttypes.h>
#include <jni.h>
#include <link.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include "shadowhook.h"
#include "include/easylsp_native_plugin.h"

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
    uint32_t phase;
    void *replacement, **original, *stub;
    _Atomic uint64_t hits;
    _Atomic int state;
};
struct subscription {
    struct selector selector;
    uint32_t phases, id;
    sh_module_callback_v1 callback;
    void *user;
};
struct plugin {
    sh_plugin_context_v1 context;
    char plugin_id[64], build_id[64], instance_id[64], package_name[256], process_name[256], generation[64], config[8193];
    void *library;
    int registering;
    _Atomic int active;
    _Atomic unsigned hook_count, subscription_count;
    struct hook hooks[MAX_HOOKS];
    struct subscription subscriptions[16];
};
static struct plugin *instances[MAX_INSTANCES];
static _Atomic unsigned instance_count;
static pthread_mutex_t events_lock = PTHREAD_MUTEX_INITIALIZER;
static char events[EVENT_COUNT][EVENT_BYTES];
static unsigned event_end, event_size;
static _Atomic uint64_t sequence, dropped;

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
    char message[513], line[EVENT_BYTES];
    escape(message, sizeof(message), detail);
    uint64_t seq = atomic_fetch_add(&sequence, 1) + 1;
    struct timespec now; clock_gettime(CLOCK_REALTIME, &now);
    uint64_t timestamp = (uint64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
    snprintf(line, sizeof(line), "{\"schema\":1,\"uid\":%u,\"pid\":%u,\"startTicks\":%" PRIu64
        ",\"instanceId\":\"%s\",\"pluginId\":\"%s\",\"buildId\":\"%s\",\"generation\":\"%s\","
        "\"timestamp\":%" PRIu64 ",\"sourceRevision\":%" PRIu64 ",\"sequence\":%" PRIu64 ",\"dropped\":%" PRIu64
        ",\"hookId\":%u,\"phase\":\"%s\",\"code\":%d,\"detail\":\"%s\"}",
        p->context.uid, p->context.pid, p->context.start_ticks, p->instance_id, p->plugin_id, p->build_id,
        p->generation, timestamp, p->context.source_revision, seq, atomic_load(&dropped), hook, phase, code, message);
    if (pthread_mutex_trylock(&events_lock) == 0) {
        memcpy(events[event_end], line, strlen(line) + 1);
        event_end = (event_end + 1) % EVENT_COUNT;
        if (event_size < EVENT_COUNT) event_size++; else atomic_fetch_add(&dropped, 1);
        pthread_mutex_unlock(&events_lock);
    } else atomic_fetch_add(&dropped, 1);
    __android_log_write(ANDROID_LOG_INFO, "EasylspNative", line);
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
static const sh_host_v1 host = { EASYLSP_NATIVE_ABI_V1, sizeof(sh_host_v1), hook_symbol, plugin_log, hit, hook_symbol_selected, hook_elf_vaddr, subscribe_module };
static int copy_string(JNIEnv *env, jstring value, char *destination, size_t capacity, int identifier) {
    if (!value || (size_t)(*env)->GetStringUTFLength(env, value) >= capacity) return SH_INVALID;
    const char *source = (*env)->GetStringUTFChars(env, value, NULL);
    if (!source) return SH_LIMIT;
    int code = 0;
    if (identifier) for (const char *c = source; *c; c++)
        if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '-' || *c == '_')) { code = SH_INVALID; break; }
    if (!code) strcpy(destination, source);
    (*env)->ReleaseStringUTFChars(env, value, source);
    return code;
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_loadPluginFd(JNIEnv *env, jclass type,
        jint fd, jstring plugin_id, jstring build_id, jstring instance_id, jstring package_name,
        jstring process_name, jstring generation, jlong revision, jstring config) {
    (void)type;
    int result = sh_engine_initialize();
    if (result) return result;
    pthread_once(&hooks_once, initialize_hooks);
    if (hooks_result) return hooks_result;
    if (instance_count == MAX_INSTANCES) return SH_LIMIT;
    struct plugin *p = calloc(1, sizeof(*p));
    if (!p) return SH_LIMIT;
    if (copy_string(env, plugin_id, p->plugin_id, sizeof(p->plugin_id), 1) ||
        copy_string(env, build_id, p->build_id, sizeof(p->build_id), 1) ||
        copy_string(env, instance_id, p->instance_id, sizeof(p->instance_id), 1) ||
        copy_string(env, package_name, p->package_name, sizeof(p->package_name), 0) ||
        copy_string(env, process_name, p->process_name, sizeof(p->process_name), 0) ||
        copy_string(env, generation, p->generation, sizeof(p->generation), 1) ||
        copy_string(env, config, p->config, sizeof(p->config), 0)) { free(p); return SH_INVALID; }
    for (unsigned i = 0; i < instance_count; i++) if (!strcmp(instances[i]->plugin_id, p->plugin_id)) { free(p); return SH_INVALID; }
    p->context = (sh_plugin_context_v1){ EASYLSP_NATIVE_ABI_V1, sizeof(sh_plugin_context_v1),
        p->plugin_id, p->build_id, p->instance_id, p->package_name, p->process_name, p->generation,
        (uint64_t)revision, start_ticks(), getuid(), getpid(), p->config, p };
    instances[instance_count] = p;
    atomic_fetch_add(&instance_count, 1);
    atomic_store(&p->active, 1);
    event(p, 0, "plugin_loading", 0, "remote FD; RTLD_LOCAL");
    android_dlextinfo extension = { .flags = ANDROID_DLEXT_USE_LIBRARY_FD, .library_fd = fd };
    char name[160]; snprintf(name, sizeof(name), "easylsp-%s-%s.so", p->plugin_id, p->build_id);
    p->library = android_dlopen_ext(name, RTLD_NOW | RTLD_LOCAL, &extension);
    if (!p->library) { event(p, 0, "plugin_load_failed", SH_LOAD_FAILED, dlerror()); return SH_LOAD_FAILED; }
    sh_plugin_init_v1 init = (sh_plugin_init_v1)dlsym(p->library, "easylsp_plugin_init_v1");
    if (!init) { event(p, 0, "plugin_load_failed", SH_ENTRY_MISSING, dlerror()); return SH_ENTRY_MISSING; }
    p->registering = 1;
    result = init(&host, &p->context);
    p->registering = 0;
    if (result) dl_iterate_phdr(cleanup_failed_instance, p);
    /* Keep the library and callback/original-pointer storage mapped even after failed init. */
    event(p, 0, result == 0 ? "plugin_initialized" : "plugin_init_failed", result, "");
    return result;
}
JNIEXPORT jobjectArray JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_readEvents(JNIEnv *env, jclass type) {
    (void)type;
    jclass string = (*env)->FindClass(env, "java/lang/String");
    pthread_mutex_lock(&events_lock);
    jobjectArray result = (*env)->NewObjectArray(env, (jsize)event_size, string, NULL);
    if (result) for (unsigned i = 0; i < event_size; i++) {
        jstring item = (*env)->NewStringUTF(env, events[(event_end + EVENT_COUNT - event_size + i) % EVENT_COUNT]);
        if (!item) break;
        (*env)->SetObjectArrayElement(env, result, (jsize)i, item);
        (*env)->DeleteLocalRef(env, item);
    }
    pthread_mutex_unlock(&events_lock);
    return result;
}
