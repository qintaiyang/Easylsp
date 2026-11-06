#ifndef EASYLSP_NATIVE_PLUGIN_H
#define EASYLSP_NATIVE_PLUGIN_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

/* Versioned C ABI. New capabilities append fields; struct_size describes available functions. */
#define EASYLSP_NATIVE_ABI_V1 1
#define EASYLSP_PLUGIN_ENTRY __attribute__((visibility("default")))
enum sh_result { SH_OK = 0, SH_WAITING = 1, SH_INVALID = -1, SH_LIMIT = -2,
    SH_MISSED_STAGE = -3, SH_AMBIGUOUS = -4, SH_LOAD_FAILED = -5, SH_ENTRY_MISSING = -6,
    SH_VERSION_MISMATCH = -7, SH_CONFLICT = -8, SH_INVALID_ADDRESS = -9,
    SH_UNSUPPORTED = -10, SH_CANCELLED = -11 };
enum sh_symbol_phase { SH_EXISTING_OR_FUTURE = 0, SH_REQUIRE_BEFORE_CONSTRUCTORS = 1 };
typedef uint32_t sh_hook_id;
typedef uint32_t sh_subscription_id;
enum sh_hook_mode { SH_EXCLUSIVE = 0, SH_CHAIN = 1 };
typedef struct sh_hook_options_v1 { uint32_t struct_size, mode; } sh_hook_options_v1;
/* ARM64: GPR x0-x30 and optional Q0-Q31 may be edited. SP/PC/PSTATE are read-only.
 * Callback runs synchronously on the intercepted thread; no I/O or blocking. */
typedef struct sh_arm64_registers_v1 {
    uint32_t struct_size, flags;
    uint64_t x[31], sp, pc, pstate;
    uint64_t q[32][2], fpsr, fpcr;
} sh_arm64_registers_v1;
typedef void (*sh_interceptor_v1)(void *user, sh_arm64_registers_v1 *registers);
enum sh_intercept_flags { SH_SIMD_READ_WRITE = 1 };
/* Exact class/loader identity is a JNI jclass supplied by the plugin. The Core
 * retains a global ref. For EXACT_LOADER this field holds a ClassLoader; NULL
 * explicitly selects the boot loader. Register requests on an attached JNI thread. */
enum sh_jni_scope { SH_JNI_EXACT_CLASS = 0, SH_JNI_EXACT_LOADER = 1 };
typedef struct sh_jni_method_v1 {
    uint32_t struct_size, scope;
    const char *class_name, *method_name, *signature;
    void *exact_class;
} sh_jni_method_v1;
enum sh_module_phase { SH_MODULE_EXISTING = 1, SH_MODULE_BEFORE_CONSTRUCTORS = 2,
    SH_MODULE_AFTER_CONSTRUCTORS = 4, SH_MODULE_BEFORE_UNLOAD = 8 };
typedef struct sh_module_selector_v1 {
    uint32_t struct_size;
    const char *name;          /* basename; optional when path is exact */
    const char *path;          /* exact linker path, including APK !/lib/... if present */
    const char *elf_build_id;  /* existing GNU Build ID, lowercase hex; required for ELF vaddr */
} sh_module_selector_v1;
typedef struct sh_module_v1 {
    uint32_t struct_size, phase;
    const char *path, *elf_build_id;
    uintptr_t load_bias;
    uint64_t load_id;          /* new identity on every observed load */
} sh_module_v1;
typedef void (*sh_module_callback_v1)(void *user, const sh_module_v1 *module);
typedef struct sh_plugin_context_v1 {
    uint32_t abi_version, struct_size;
    const char *plugin_id, *build_id, *instance_id, *package_name, *process_name, *generation;
    uint64_t source_revision, start_ticks;
    uint32_t uid, pid;
    const char *config_json;
    void *instance;
} sh_plugin_context_v1;

struct sh_host_v1;
typedef int (*sh_registration_v1)(const struct sh_host_v1 *, void *instance, void *user);

typedef struct sh_host_v1 {
    uint32_t abi_version, struct_size;
    /* Registration during init or register_tasks batch. module is a basename or exact linker pathname.
     * replacement and original storage live until process exit. Positive backend errors
     * other than SH_WAITING are ShadowHook codes; an ID alone is not installation evidence. */
    int (*hook_symbol)(void *instance, const char *module, const char *symbol, uint32_t phase,
                       void *replacement, void **original, sh_hook_id *id);
    /* Strings are copied to bounded events. Both functions preserve errno. */
    void (*log)(void *instance, const char *message);
    void (*hit)(void *instance, sh_hook_id id);
    /* Extended selector; registration during init or a Native batch. Existing returns SH_OK,
     * absent module returns SH_WAITING. Errors and future results are emitted per hook. */
    int (*hook_symbol_selected)(void *, const sh_module_selector_v1 *, const char *symbol,
        uint32_t phase, void *replacement, void **original, sh_hook_id *id);
    int (*hook_elf_vaddr)(void *, const sh_module_selector_v1 *, uintptr_t elf_vaddr,
        uint32_t phase, void *replacement, void **original, sh_hook_id *id);
    /* Synchronous callbacks: no Java, I/O, dlopen, or registration from callbacks.
     * existing is delivered as existing, never fabricated as before constructors.
     * Pointers in module are valid only during the callback. */
    int (*subscribe_module)(void *, const sh_module_selector_v1 *, uint32_t phases,
        sh_module_callback_v1 callback, void *user, sh_subscription_id *id);
    /* Appended API: old binaries retain the original layout and EXCLUSIVE behavior.
     * CHAIN uses a typed original pointer as next; first registered runs outermost. */
    int (*hook_symbol_options)(void *, const sh_module_selector_v1 *, const char *, uint32_t,
        const sh_hook_options_v1 *, void *, void **, sh_hook_id *);
    int (*hook_elf_vaddr_options)(void *, const sh_module_selector_v1 *, uintptr_t, uint32_t,
        const sh_hook_options_v1 *, void *, void **, sh_hook_id *);
    int (*intercept_elf_vaddr)(void *, const sh_module_selector_v1 *, uintptr_t, uint32_t,
        uint32_t flags, sh_interceptor_v1, void *user, sh_hook_id *);
    int (*hook_jni_method)(void *, const sh_jni_method_v1 *, const sh_hook_options_v1 *,
        void *replacement, void **original, sh_hook_id *);
    void *(*get_jni_env)(void); /* Current attached thread only; does not attach. */
    /* Exact caller PLT (JUMP_SLOT) relocation, EXCLUSIVE ownership. The callee's
     * code and other callers are unchanged. Existing or future module supported. */
    int (*hook_plt)(void *, const sh_module_selector_v1 *caller, const char *symbol,
        uint32_t phase, void *replacement, void **original, sh_hook_id *);
    /* Serialize Native registrations after init on the linker lock. Callback may
     * only register Native hooks/subscriptions: no JNI, I/O, dlopen or waiting.
     * Invoke from an ordinary plugin-owned worker, never a module/intercept callback. */
    int (*register_tasks)(void *, sh_registration_v1, void *user);
    /* Prevent future rebinding and detach Native hooks. JNI requests require VM
     * re-registration and therefore return SH_UNSUPPORTED. Storage stays alive. */
    int (*remove_hook)(void *, sh_hook_id);
    int (*unsubscribe_module)(void *, sh_subscription_id);
} sh_host_v1;

typedef int (*sh_plugin_init_v1)(const sh_host_v1 *, const sh_plugin_context_v1 *);
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *, const sh_plugin_context_v1 *);
#ifdef __cplusplus
}
#endif
#endif
