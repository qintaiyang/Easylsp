#include "easylsp_native_plugin.h"
#include <stddef.h>
static const sh_host_v1 *api;
static void *instance;
static int (*original)(int), (*future_original)(int);
static sh_hook_id active, future;
static sh_subscription_id subscription;
static int calls;
static int replacement(int x) { api->hit(instance,active); return original(x) + 100; }
static int future_replacement(int x) { api->hit(instance,future); return future_original(x) + 200; }
static void module_event(void *user, const sh_module_v1 *module) { (void)user; (void)module; calls++; }
static int install(const sh_host_v1 *host, void *owner, void *user) {
    (void)user;
    sh_module_selector_v1 caller = {sizeof(caller),"libm7caller.so",NULL,NULL};
    return host->hook_plt(owner,&caller,"m7_value",SH_EXISTING_OR_FUTURE,(void *)replacement,(void **)&original,&active);
}
/* Called from a normal thread in the fixture, outside Hook/module callbacks. */
EASYLSP_PLUGIN_ENTRY int m7_control(int operation) {
    if (operation == 0) return api->remove_hook(instance,active);
    if (operation == 1) return api->register_tasks(instance,install,NULL);
    if (operation == 2) return api->unsubscribe_module(instance,subscription);
    if (operation == 3) return calls;
    return SH_INVALID;
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *host,const sh_plugin_context_v1 *context) {
    if (host->struct_size < offsetof(sh_host_v1,unsubscribe_module) + sizeof(host->unsubscribe_module)) return SH_UNSUPPORTED;
    api=host; instance=context->instance;
    int result=install(host,instance,NULL);
    if (result != SH_OK && result != SH_WAITING) return result;
    sh_module_selector_v1 caller = {sizeof(caller),"libm7future.so",NULL,NULL};
    result=host->hook_plt(instance,&caller,"m7_value",SH_EXISTING_OR_FUTURE,(void *)future_replacement,(void **)&future_original,&future);
    if (result != SH_WAITING && result != SH_OK) return result;
    return host->subscribe_module(instance,&caller,SH_MODULE_BEFORE_CONSTRUCTORS|SH_MODULE_BEFORE_UNLOAD,module_event,NULL,&subscription);
}
