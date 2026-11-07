/* Actual Core code, with the repo's desktop Hook stubs and a fake JNI table.
 * This checks registration contracts and call paths, not ART/trampoline behavior. */
#define __ANDROID__ 1
#include "../src/main/cpp/native_plugin.c"
#include <assert.h>

int sh_engine_initialize(void) { return 0; }
static int name_calls, copied_table;
static const JNINativeMethod *submitted;
static jboolean fake_exception(JNIEnv *env) { (void)env; return JNI_FALSE; }
static jobject fake_name(JNIEnv *env, jobject cls, jmethodID method, ...) {
    (void)env; (void)cls; (void)method;
    name_calls++; return (jobject)(uintptr_t)7;
}
static const char *fake_chars(JNIEnv *env, jstring value, jboolean *copy) {
    (void)env; (void)value; (void)copy; return "review.Target";
}
static void fake_release(JNIEnv *env, jstring value, const char *chars) {
    (void)env; (void)value; (void)chars;
}
static void fake_delete(JNIEnv *env, jobject value) { (void)env; (void)value; }
static const struct JNINativeInterface_ env_api = {
    .ExceptionCheck = fake_exception, .CallObjectMethod = fake_name,
    .GetStringUTFChars = fake_chars, .ReleaseStringUTFChars = fake_release,
    .DeleteLocalRef = fake_delete
};
static JNIEnv environment = &env_api;
static jint fake_get_env(JavaVM *vm, void **env, jint version) {
    (void)vm; (void)version; *env = &environment; return JNI_OK;
}
static const struct JNIInvokeInterface_ vm_api = {.GetEnv = fake_get_env};
static JavaVM virtual_machine = &vm_api;
static jint fake_register(JNIEnv *env, jclass cls, const JNINativeMethod *methods, jint count) {
    (void)env; (void)cls; assert(count == 1);
    copied_table = methods != submitted;
    assert(methods[0].fnPtr == submitted[0].fnPtr);
    return JNI_OK;
}
static void replacement(void) {}
static struct plugin plugin;
static void *original;
int main(void) {
    jni_vm = &virtual_machine;
    jni_original_register = fake_register;
    jni_class_name = (jmethodID)(uintptr_t)1;
    JNINativeMethod methods[] = {{"work", "()V", (void *)replacement}};
    submitted = methods;
    assert(instance_count == 0);
    assert(jni_register_proxy(&environment, (jclass)(uintptr_t)1, methods, 1) == JNI_OK);
    assert(name_calls == 0 && copied_table == 0);
    printf("zero JNI requests: Class.getName calls=%d, copied table=%d\n", name_calls, copied_table);

    atomic_store(&plugin.active, 1);
    sh_jni_method_v1 method = {sizeof(method), SH_JNI_EXACT_LOADER, "review.Target", "work", "()V", NULL};
    sh_hook_options_v1 options = {sizeof(options), SH_EXCLUSIVE};
    sh_hook_id id = 0;
    int outside_init = hook_jni_method(&plugin, &method, &options, (void *)replacement, &original, &id);
    assert(outside_init == SH_WAITING);
    plugin.registering = 1;
    registration_owner = &plugin;
    int inside_init = hook_jni_method(&plugin, &method, &options, (void *)replacement, &original, &id);
    assert(inside_init == SH_WAITING);
    plugin.registering = 0;
    registration_owner = NULL;
    printf("attached-thread JNI request: after init=%d (SH_WAITING), during init=%d (SH_WAITING)\n", outside_init, inside_init);
}
