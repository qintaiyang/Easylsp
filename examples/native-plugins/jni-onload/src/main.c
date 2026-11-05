#include "easylsp_native_plugin.h"
#include <jni.h>
#include <errno.h>
static const sh_host_v1 *host;
static void *instance;
static sh_hook_id hook;
static jint (*original_onload)(JavaVM *, void *);
static jint replace_onload(JavaVM *vm, void *reserved) {
    host->hit(instance, hook);
    host->log(instance, "JNI_OnLoad wrapper before original");
    jint result = original_onload(vm, reserved);
    int saved_errno = errno;
    host->log(instance, "JNI_OnLoad wrapper after original");
    errno = saved_errno;
    return result;
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    if (api->abi_version != 1 || api->struct_size < sizeof(*api)) return SH_INVALID;
    host = api; instance = context->instance;
    int code = host->hook_symbol(instance, "libdemo_early_hook.so", "JNI_OnLoad", SH_REQUIRE_BEFORE_CONSTRUCTORS,
        (void *)replace_onload, (void **)&original_onload, &hook);
    return code == SH_WAITING ? SH_OK : code;
}
