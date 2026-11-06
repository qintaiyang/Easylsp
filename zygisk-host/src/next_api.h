#ifndef EASYLSP_NEXT_API_H
#define EASYLSP_NEXT_API_H
/* Independently declared interoperability layout from the public Next API v3/v4
 * specification. No provider implementation is included. Unused API slots retain
 * their published pointer-sized positions on arm64 Android. */
#include <stddef.h>
struct eh_next_runtime {
    int type, api_version;
    int (*register_module)(const void *module);
};
struct eh_next_api {
    void (*unused_hook_and_resolver_slots[8])(void);
    int (*connect_companion)(void *handle);
    const struct eh_next_runtime *(*get_runtime)(void); /* API v4 only */
};
struct eh_next_module { int target_api_version; void (*loaded)(void *, const struct eh_next_api *); };
struct eh_next_companion { int target_api_version; void (*loaded)(void); void (*connected)(int); };
struct eh_hyos_args { const char *process_name, *package_name, *se_info; };
struct eh_hyos_module { int target_api_version; void (*specialized)(const struct eh_hyos_args *); };
#endif
