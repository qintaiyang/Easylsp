#include "easylsp_native_plugin.h"
#include <jni.h>
static const sh_host_v1 *host;
static void *instance;
static sh_hook_id registration_hook, onload_hook;
static jint (*next_value)(JNIEnv *, jclass, jint);
static jint (*next_onload)(JavaVM *, void *);
static jint replacement(JNIEnv *env, jclass cls, jint value) {
    jint result = next_value(env, cls, value + 3);
    host->hit(instance, registration_hook); return result * 10;
}
static jint onload(JavaVM *vm, void *reserved) {
    JNIEnv *env; if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "io/github/easylsp/demo/DynamicTarget");
    if (!cls) return JNI_ERR;
    sh_jni_method_v1 method = {sizeof(method), SH_JNI_EXACT_CLASS, "io.github.easylsp.demo.DynamicTarget", "value", "(I)I", cls};
    sh_hook_options_v1 options = {sizeof(options), SH_CHAIN};
    int code = host->hook_jni_method(instance, &method, &options, (void *)replacement, (void **)&next_value, &registration_hook);
    (*env)->DeleteLocalRef(env, cls);
    if (code != SH_OK && code != SH_WAITING) return JNI_ERR;
    return next_onload(vm, reserved);
}
EASYLSP_PLUGIN_ENTRY int easylsp_plugin_init_v1(const sh_host_v1 *api, const sh_plugin_context_v1 *context) {
    if (api->struct_size < sizeof(*api)) return SH_INVALID;
    host = api; instance = context->instance;
    sh_module_selector_v1 selector = {sizeof(selector), "libdemo_dynamic_jni.so", 0, 0};
    sh_hook_options_v1 options = {sizeof(options), SH_CHAIN};
    int code = host->hook_symbol_options(instance, &selector, "JNI_OnLoad", SH_REQUIRE_BEFORE_CONSTRUCTORS, &options, (void *)onload, (void **)&next_onload, &onload_hook);
    return code == SH_OK || code == SH_WAITING ? SH_OK : code;
}
