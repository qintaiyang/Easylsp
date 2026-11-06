#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdio.h>
JNIEXPORT jint JNICALL Java_io_github_easylsp_demo_NamespaceProbe_claim(JNIEnv *env, jclass cls, jstring directory, jstring backend) {
    (void)cls;
    const char *base = (*env)->GetStringUTFChars(env, directory, NULL);
    const char *name = (*env)->GetStringUTFChars(env, backend, NULL);
    char path[1024]; snprintf(path, sizeof(path), "%s/libshadowhook.so", base);
    void *shadow = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    snprintf(path, sizeof(path), "%s/libeasylsp_native.so", base);
    void *core = shadow ? dlopen(path, RTLD_NOW | RTLD_LOCAL) : NULL;
    int (*claim)(const char *) = core ? (int (*)(const char *))dlsym(core, "sh_core_claim") : NULL;
    int result = claim ? claim(name) : -100;
    __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "namespace directory=%s core=%p claim=%p backend=%s result=%d error=%s", base, core, (void *)claim, name, result, claim ? "none" : dlerror());
    (*env)->ReleaseStringUTFChars(env, directory, base); (*env)->ReleaseStringUTFChars(env, backend, name);
    return result;
}
