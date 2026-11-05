#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <dlfcn.h>
#include <link.h>
#include <signal.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include "shadowhook.h"
#include "diagnostic_wire.h"

static int channel = -1;
static pthread_once_t engine_once = PTHREAD_ONCE_INIT;
static int engine_result;
static void initialize_engine(void) { engine_result = shadowhook_init(SHADOWHOOK_MODE_UNIQUE, false); }
int sh_engine_initialize(void) { pthread_once(&engine_once, initialize_engine); return engine_result; }
JNIEXPORT jint JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_initializeEngine(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return sh_engine_initialize();
}
static _Atomic uint64_t sequence, dropped;
static uint64_t millis(clockid_t id) { struct timespec t; clock_gettime(id, &t); return (uint64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000; }
/* No JNI, allocation, stack unwinding or locks in wrappers/linker callbacks. Kernel queue is bounded. */
static void emit(uint32_t kind, const char *name, uintptr_t pc, uint64_t a, uint64_t b, uint64_t c) {
    int saved_errno = errno;
    if (channel < 0) return;
    struct sh_diag_event e = {0};
    e.version = SH_DIAG_VERSION; e.kind = kind; e.pid = getpid(); e.tid = (uint32_t)syscall(SYS_gettid);
    e.sequence = atomic_fetch_add_explicit(&sequence, 1, memory_order_relaxed) + 1;
    e.dropped = atomic_load_explicit(&dropped, memory_order_relaxed);
    e.wall_ms = millis(CLOCK_REALTIME); e.elapsed_ms = millis(CLOCK_BOOTTIME);
    e.pc = pc; e.a = a; e.b = b; e.c = c;
    size_t length = strnlen(name, sizeof(e.name));
    if (length == sizeof(e.name)) { length--; e.flags |= 1; }
    memcpy(e.name, name, length);
    if (send(channel, &e, sizeof(e), MSG_DONTWAIT | MSG_NOSIGNAL) != (ssize_t)sizeof(e)) atomic_fetch_add_explicit(&dropped, 1, memory_order_relaxed);
    errno = saved_errno;
}
static void module_event(struct dl_phdr_info *info, uint32_t kind) {
    uintptr_t low = UINTPTR_MAX, high = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; i++) if (info->dlpi_phdr[i].p_type == PT_LOAD) {
        uintptr_t begin = info->dlpi_addr + info->dlpi_phdr[i].p_vaddr;
        uintptr_t end = begin + info->dlpi_phdr[i].p_memsz;
        if (begin < low) low = begin;
        if (end > high) high = end;
    }
    emit(kind, info->dlpi_name, 0, info->dlpi_addr, low, high);
}
static int existing(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; module_event(info, SH_EXISTING); return 0; }
static void before_init(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; module_event(info, SH_BEFORE_INIT); }
static void before_fini(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; module_event(info, SH_BEFORE_FINI); }
static void after_init(struct dl_phdr_info *info, size_t size, void *data) { (void)size; (void)data; module_event(info, SH_AFTER_INIT); }

static void (*previous_abort)(void);
static void (*previous_exit)(int);
static void (*previous__exit)(int);
static int (*previous_kill)(pid_t, int);
static int (*previous_tgkill)(pid_t, pid_t, int);
static void proxy_abort(void) { emit(SH_EXIT, "abort", (uintptr_t)__builtin_return_address(0), 0, 0, 0); previous_abort(); }
static void proxy_exit(int status) { emit(SH_EXIT, "exit", (uintptr_t)__builtin_return_address(0), (uint64_t)(int64_t)status, 0, 0); previous_exit(status); }
static void proxy__exit(int status) { emit(SH_EXIT, "_exit", (uintptr_t)__builtin_return_address(0), (uint64_t)(int64_t)status, 0, 0); previous__exit(status); }
static int proxy_kill(pid_t pid, int sig) { emit(SH_EXIT, "kill", (uintptr_t)__builtin_return_address(0), (uint64_t)(int64_t)pid, sig, 0); return previous_kill(pid, sig); }
static int proxy_tgkill(pid_t tgid, pid_t tid, int sig) { emit(SH_EXIT, "tgkill", (uintptr_t)__builtin_return_address(0), tgid, tid, sig); return previous_tgkill(tgid, tid, sig); }
extern const char *sh_native_address_owner(void *address);
extern void sh_native_record_probe(void *address, const char *name);
static void hook(const char *name, void *proxy, void **previous) {
    void *address = dlsym(RTLD_DEFAULT, name);
    const char *owner = sh_native_address_owner(address);
    if (owner) { emit(SH_PROBE, owner, (uintptr_t)address, SHADOWHOOK_ERRNO_HOOK_DUP, 0, 0); return; }
    void *stub = shadowhook_hook_sym_name("libc.so", name, proxy, previous);
    if (stub) sh_native_record_probe(address, name);
    emit(SH_PROBE, name, 0, stub == NULL ? (uint64_t)shadowhook_get_errno() : 0, 0, 0);
}

JNIEXPORT jint JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_startObservation(JNIEnv *env, jclass type, jstring endpoint) {
    (void)type;
    if (channel >= 0) return 0;
    const char *name = (*env)->GetStringUTFChars(env, endpoint, NULL);
    struct sockaddr_un address = { .sun_family = AF_UNIX };
    size_t length = strlen(name);
    if (length >= sizeof(address.sun_path) - 1) { (*env)->ReleaseStringUTFChars(env, endpoint, name); return -ENAMETOOLONG; }
    memcpy(address.sun_path + 1, name, length);
    (*env)->ReleaseStringUTFChars(env, endpoint, name);
    int fd = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return -errno;
    int buffer_bytes = 1024 * 1024;
    if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buffer_bytes, sizeof(buffer_bytes)) != 0) { int code = errno; close(fd); return -code; }
    socklen_t buffer_size = sizeof(buffer_bytes);
    if (getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buffer_bytes, &buffer_size) != 0) { int code = errno; close(fd); return -code; }
    if (connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + length + 1) != 0) { int code = errno; close(fd); return -code; }
    struct ucred peer; socklen_t size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &size) != 0 || peer.uid != 0) { close(fd); return -EACCES; }
    channel = fd;
    int result = sh_engine_initialize();
    emit(SH_INIT, shadowhook_get_version(), 0, (uint64_t)result, (uint64_t)buffer_bytes, 0);
    if (result != 0) { close(channel); channel = -1; return result; }
    int lifecycle = shadowhook_register_dl_init_callback(before_init, after_init, NULL);
    emit(SH_PROBE, "library_init_callbacks", 0, (uint64_t)lifecycle, 0, 0);
    emit(SH_PROBE, "library_fini_callbacks", 0, (uint64_t)shadowhook_register_dl_fini_callback(before_fini, NULL, NULL), 0, 0);
    hook("abort", (void *)proxy_abort, (void **)&previous_abort);
    hook("exit", (void *)proxy_exit, (void **)&previous_exit);
    hook("_exit", (void *)proxy__exit, (void **)&previous__exit);
    hook("kill", (void *)proxy_kill, (void **)&previous_kill);
    hook("tgkill", (void *)proxy_tgkill, (void **)&previous_tgkill);
    dl_iterate_phdr(existing, NULL);
    return 0;
}
