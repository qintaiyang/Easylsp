#include "easylsp_native_plugin.h"
#include <stdio.h>
static const sh_host_v1 *host;
static void *instance;
static void on_module(void *user, const sh_module_v1 *module) {
    (void)user;
    char text[200];
    snprintf(text, sizeof(text), "lifecycle phase=%u loadId=%llu bias=%p", module->phase,
        (unsigned long long)module->load_id, (void *)module->load_bias);
    host->log(instance, text);
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    if (api->abi_version != 1 || api->struct_size < sizeof(*api)) return SH_INVALID;
    host = api; instance = context->instance;
    const sh_module_selector_v1 selector = {sizeof(selector), "libdemo_early_hook.so", NULL, NULL};
    sh_subscription_id subscription;
    return host->subscribe_module(instance, &selector, SH_MODULE_EXISTING | SH_MODULE_BEFORE_CONSTRUCTORS |
        SH_MODULE_AFTER_CONSTRUCTORS | SH_MODULE_BEFORE_UNLOAD, on_module, NULL, &subscription);
}
