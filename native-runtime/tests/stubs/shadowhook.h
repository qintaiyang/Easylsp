#include <dlfcn.h>
#include <stddef.h>
#define SHADOWHOOK_ERRNO_HOOK_DLSYM 7
static int unhook_result;
static inline void *shadowhook_dlopen(const char *name) { return dlopen(name, RTLD_NOW | RTLD_NOLOAD); }
static inline void *shadowhook_dlsym(void *handle, const char *name) { return dlsym(handle, name); }
static inline void shadowhook_dlclose(void *handle) { dlclose(handle); }
static inline void *shadowhook_hook_sym_addr(void *address, void *replacement, void **original) {
    (void)replacement; *original = address; return address;
}
static inline void *shadowhook_hook_func_addr(void *address, void *replacement, void **original) {
    return shadowhook_hook_sym_addr(address, replacement, original);
}
static inline int shadowhook_get_errno(void) { return 0; }
static inline int shadowhook_unhook(void *stub) { (void)stub; return unhook_result; }
typedef void (*test_module_callback)(struct dl_phdr_info *, size_t, void *);
static inline int shadowhook_register_dl_init_callback(test_module_callback before, test_module_callback after, void *data) {
    (void)before; (void)after; (void)data; return 0;
}
static inline int shadowhook_register_dl_fini_callback(test_module_callback before, test_module_callback after, void *data) {
    return shadowhook_register_dl_init_callback(before, after, data);
}

/* Desktop fixture models registration/ownership only; it does not execute trampolines. */
#define SHADOWHOOK_HOOK_WITH_UNIQUE_MODE 2
#define SHADOWHOOK_HOOK_WITH_MULTI_MODE 4
#define SHADOWHOOK_INTERCEPT_DEFAULT 0
#define SHADOWHOOK_INTERCEPT_WITH_FPSIMD_READ_WRITE 3
typedef struct { unsigned long long regs[31], sp, pc, pstate, vregs[32][2], fpsr, fpcr; } shadowhook_cpu_context_t;
static inline void *shadowhook_hook_sym_addr_2(void *address, void *replacement, void **original, unsigned flags) {
    (void)flags; return shadowhook_hook_sym_addr(address, replacement, original);
}
static inline void *shadowhook_hook_func_addr_2(void *address, void *replacement, void **original, unsigned flags) {
    return shadowhook_hook_sym_addr_2(address, replacement, original, flags);
}
static inline void *shadowhook_intercept_instr_addr(void *address, void (*callback)(shadowhook_cpu_context_t *, void *), void *data, unsigned flags) {
    (void)callback; (void)data; (void)flags; return address;
}
static inline int shadowhook_unintercept(void *stub) { return shadowhook_unhook(stub); }
