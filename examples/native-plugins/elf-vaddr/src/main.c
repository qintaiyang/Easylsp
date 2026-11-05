#include "easylsp_native_plugin.h"
#include <errno.h>
/* Replace these with the same NativeAnalysis sample's existing GNU Build ID and ELF_VADDR. */
#define TARGET_ELF_BUILD_ID "REPLACE_FROM_ANALYSIS"
#define TARGET_ELF_VADDR 0x0

static const sh_host_v1 *host;
static void *instance;
static sh_hook_id hook;
static int (*original_value)(int);
static int replace_value(int value) {
    /* Argument handling, original call and return processing use the actual int(int) ABI. */
    int result = original_value(value + 2);
    int saved_errno = errno;
    host->hit(instance, hook);
    errno = saved_errno;
    return result + 1000;
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    if (api->abi_version != EASYLSP_NATIVE_ABI_V1 || api->struct_size < sizeof(sh_host_v1)) return SH_INVALID;
    host = api;
    instance = context->instance;
    host->log(instance, "early-symbol init: constructor should return 1010, original returns 8");
    const sh_module_selector_v1 selector = {sizeof(selector), "libdemo_early_hook.so", NULL, TARGET_ELF_BUILD_ID};
    int result = host->hook_elf_vaddr(instance, &selector, TARGET_ELF_VADDR,
        SH_REQUIRE_BEFORE_CONSTRUCTORS, (void *)replace_value, (void **)&original_value, &hook);
    return result == SH_OK || result == SH_WAITING ? SH_OK : result;
}
