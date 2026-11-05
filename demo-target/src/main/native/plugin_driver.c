#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
JNIEXPORT void JNICALL Java_io_github_easylsp_demo_DemoApplication_nativePluginLifecycle(JNIEnv *env, jclass type) {
    (void)type;
    JavaVM *vm; (*env)->GetJavaVM(env, &vm);
    for (int cycle = 1; cycle <= 2; cycle++) {
        void *module = dlopen("libdemo_early_hook.so", RTLD_NOW | RTLD_LOCAL);
        if (!module) { __android_log_print(ANDROID_LOG_ERROR, "EasylspDemoNative", "dlopen failed: %s", dlerror()); return; }
        int (*value)(int) = (int (*)(int))dlsym(module, "easylsp_demo_early_value");
        jint (*onload)(JavaVM *, void *) = (jint (*)(JavaVM *, void *))dlsym(module, "JNI_OnLoad");
        __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "cycle=%d value=%d JNI=%x", cycle, value(7), onload(vm, NULL));
        dlclose(module);
        __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "cycle=%d closed", cycle);
    }
}
