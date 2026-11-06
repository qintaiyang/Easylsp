#include <jni.h>
#include <android/log.h>
static jint value(JNIEnv *env, jclass cls, jint input) { (void)env; (void)cls; return input + 1; }
static jint overloaded(JNIEnv *env, jclass cls, jint left, jint right) { (void)env; (void)cls; return left + right; }
static jint register_class(JNIEnv *env, jclass cls) {
    JNINativeMethod methods[] = {{"value", "(I)I", (void *)value}, {"value", "(II)I", (void *)overloaded}};
    return (*env)->RegisterNatives(env, cls, methods, 2);
}
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved; JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass cls = (*env)->FindClass(env, "io/github/easylsp/demo/DynamicTarget");
    if (!cls || register_class(env, cls)) return JNI_ERR;
    (*env)->DeleteLocalRef(env, cls); return JNI_VERSION_1_6;
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_demo_DemoApplication_registerDynamic(JNIEnv *env, jclass type, jclass target) {
    (void)type; return register_class(env, target);
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_demo_DemoApplication_unregisterDynamic(JNIEnv *env, jclass type, jclass target) {
    (void)type; return (*env)->UnregisterNatives(env, target);
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_demo_DemoApplication_failDynamic(JNIEnv *env, jclass type, jclass target) {
    (void)type; JNINativeMethod method = {"missing", "(I)I", (void *)value};
    jint code = (*env)->RegisterNatives(env, target, &method, 1);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "JNI failure pending exception preserved code=%d", code); }
    return code;
}
