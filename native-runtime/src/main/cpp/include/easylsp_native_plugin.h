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
    SH_VERSION_MISMATCH = -7, SH_CONFLICT = -8, SH_INVALID_ADDRESS = -9 };
enum sh_symbol_phase { SH_EXISTING_OR_FUTURE = 0, SH_REQUIRE_BEFORE_CONSTRUCTORS = 1 };
typedef uint32_t sh_hook_id;
typedef uint32_t sh_subscription_id;
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

typedef struct sh_host_v1 {
    uint32_t abi_version, struct_size;
    /* Registration only during init. module is a basename or exact linker pathname.
     * replacement and original storage live until process exit. Positive backend errors
     * other than SH_WAITING are ShadowHook codes; an ID alone is not installation evidence. */
    int (*hook_symbol)(void *instance, const char *module, const char *symbol, uint32_t phase,
                       void *replacement, void **original, sh_hook_id *id);
    /* Strings are copied to bounded events. Both functions preserve errno. */
    void (*log)(void *instance, const char *message);
    void (*hit)(void *instance, sh_hook_id id);
    /* Extended selector; registration during init only. Existing returns SH_OK,
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
} sh_host_v1;

typedef int (*sh_plugin_init_v1)(const sh_host_v1 *, const sh_plugin_context_v1 *);
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *, const sh_plugin_context_v1 *);
#ifdef __cplusplus
}
#endif
#endif
