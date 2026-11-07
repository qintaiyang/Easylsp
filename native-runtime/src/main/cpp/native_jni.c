#include <jni.h>
#include "include/easylsp_native_core.h"
#include "include/easylsp_native_plugin.h"

JNIEXPORT jint JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_initializeEngine(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    int result = sh_core_claim("LIBXPOSED");
    if (!result) result = sh_engine_initialize();
    if (!result) result = sh_core_set_jni_env(env);
    return result;
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_startObservation(JNIEnv *env, jclass type, jstring endpoint) {
    (void)type;
    const char *name = (*env)->GetStringUTFChars(env, endpoint, NULL);
    if (!name) return SH_LIMIT;
    int result = sh_core_claim("LIBXPOSED");
    if (!result) result = sh_core_start_observation(name);
    (*env)->ReleaseStringUTFChars(env, endpoint, name);
    return result;
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_loadPluginFd(JNIEnv *env, jclass type,
        jint fd, jstring plugin_id, jstring build_id, jstring instance_id, jstring package_name,
        jstring process_name, jstring generation, jlong revision, jstring config, jstring release) {
    (void)type;
    jstring values[] = {plugin_id, build_id, instance_id, package_name, process_name, generation, config, release};
    const char *strings[8] = {0};
    int result = SH_INVALID;
    for (unsigned i = 0; i < 8; i++) {
        if (!values[i]) goto release;
        strings[i] = (*env)->GetStringUTFChars(env, values[i], NULL);
        if (!strings[i]) { result = SH_LIMIT; goto release; }
    }
    result = sh_core_claim("LIBXPOSED");
    if (!result) result = sh_engine_initialize();
    if (!result) result = sh_core_set_jni_env(env);
    if (!result) {
        sh_core_plugin_v1 request = {sizeof(request), fd, strings[0], strings[1], strings[2], strings[3], strings[4], strings[5], (uint64_t)revision, strings[6], strings[7]};
        result = sh_core_load_plugin_fd(&request);
    }
release:
    for (unsigned i = 0; i < 8; i++) if (strings[i]) (*env)->ReleaseStringUTFChars(env, values[i], strings[i]);
    return result;
}
struct event_list { JNIEnv *env; jobject list; jmethodID add; };
static void append_event(void *user, const char *json) {
    struct event_list *list = user;
    if ((*list->env)->ExceptionCheck(list->env)) return;
    jstring item = (*list->env)->NewStringUTF(list->env, json);
    if (!item) return;
    (*list->env)->CallBooleanMethod(list->env, list->list, list->add, item);
    (*list->env)->DeleteLocalRef(list->env, item);
}
JNIEXPORT jobjectArray JNICALL Java_io_github_easylsp_nativeengine_NativeRuntime_readEvents(JNIEnv *env, jclass type) {
    (void)type;
    jclass array_list = (*env)->FindClass(env, "java/util/ArrayList");
    if (!array_list) return NULL;
    jobject list = (*env)->NewObject(env, array_list, (*env)->GetMethodID(env, array_list, "<init>", "()V"));
    if (!list) return NULL;
    struct event_list buffer = {env, list, (*env)->GetMethodID(env, array_list, "add", "(Ljava/lang/Object;)Z")};
    static uint64_t cursor;
    sh_core_read_events_since(&cursor, append_event, &buffer);
    if ((*env)->ExceptionCheck(env)) return NULL;
    jclass string = (*env)->FindClass(env, "java/lang/String");
    if (!string) return NULL;
    jobjectArray empty = (*env)->NewObjectArray(env, 0, string, NULL);
    return (jobjectArray)(*env)->CallObjectMethod(env, list, (*env)->GetMethodID(env, array_list, "toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;"), empty);
}
