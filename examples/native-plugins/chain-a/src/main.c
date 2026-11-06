#include "easylsp_native_plugin.h"
#include <errno.h>
static const sh_host_v1 *host;
static void *instance;
static sh_hook_id hook;
static int (*next_value)(int);
static int (*next_recursive)(int);
static sh_hook_id recursive_hook;
static int recursive_replacement(int value) { int result = next_recursive(value); return result + 10; }
static int replacement(int value) {
    int result = next_value(value + 2); int error = errno;
    host->hit(instance, hook); errno = error; return result + 1000;
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    if (api->struct_size < sizeof(*api)) return SH_INVALID;
    host = api; instance = context->instance;
    sh_module_selector_v1 selector = {sizeof(selector), "libdemo_early_hook.so", 0, 0};
    sh_hook_options_v1 options = {sizeof(options), SH_CHAIN};
    int code = api->hook_symbol_options(instance, &selector, "easylsp_demo_early_value", SH_REQUIRE_BEFORE_CONSTRUCTORS,
        &options, (void *)replacement, (void **)&next_value, &hook);
    if (code != SH_OK && code != SH_WAITING) return code;
    code = api->hook_symbol_options(instance, &selector, "easylsp_demo_recursive", SH_REQUIRE_BEFORE_CONSTRUCTORS,
        &options, (void *)recursive_replacement, (void **)&next_recursive, &recursive_hook);
    return code == SH_OK || code == SH_WAITING ? SH_OK : code;
}
