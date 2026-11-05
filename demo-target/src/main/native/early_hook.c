#include <android/log.h>
#include <jni.h>
#include <errno.h>

static int constructor_result;
/* A real, public C signature; no inline folding into the constructor. */
__attribute__((visibility("default"), noinline)) int easylsp_demo_early_value(int value) {
    errno = EDOM;
    return value + 1;
}
__attribute__((constructor)) static void initialize(void) {
    constructor_result = easylsp_demo_early_value(7);
    __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "constructor result=%d errno=%d", constructor_result, errno);
}
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "JNI_OnLoad constructor_result=%d", constructor_result);
    return JNI_VERSION_1_6;
}
JNIEXPORT jint JNICALL Java_io_github_easylsp_demo_DemoApplication_earlyConstructorResult(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    return constructor_result;
}
