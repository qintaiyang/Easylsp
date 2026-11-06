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
#include <pthread.h>
#include <stdatomic.h>
#include <errno.h>
#include <time.h>
static int (*stress_value)(int);
static int stress_expected;
static _Atomic int stress_errors;
static void *stress_worker(void *unused) {
    (void)unused;
    for (int i = 0; i < 1000; i++) {
        errno = 0; int value = stress_value(7);
        if (value != stress_expected || errno != EDOM) atomic_fetch_add(&stress_errors, 1);
    }
    return NULL;
}
JNIEXPORT void JNICALL Java_io_github_easylsp_demo_DemoApplication_nativeM4Checks(JNIEnv *env, jclass type, jint expected) {
    (void)env; (void)type;
    void *module = dlopen("libdemo_early_hook.so", RTLD_NOW | RTLD_NOLOAD);
    stress_value = (int (*)(int))dlsym(module, "easylsp_demo_early_value"); stress_expected = expected;
    atomic_store(&stress_errors, 0);
    pthread_t threads[4]; int launched = 0;
    for (int i = 0; i < 4; i++) { if (pthread_create(&threads[i], NULL, stress_worker, NULL)) break; launched++; }
    for (int i = 0; i < launched; i++) pthread_join(threads[i], NULL);
    struct timespec before, after; clock_gettime(CLOCK_MONOTONIC, &before);
    volatile int result = 0; for (int i = 0; i < 100000; i++) result = stress_value(7);
    clock_gettime(CLOCK_MONOTONIC, &after);
    long long elapsed = (after.tv_sec - before.tv_sec) * 1000000000LL + after.tv_nsec - before.tv_nsec;
    int (*recursive)(int) = (int (*)(int))dlsym(module, "easylsp_demo_recursive");
    __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "M4 threads=%d errors=%d result=%d recursive=%d nsPerCall=%.1f", launched, atomic_load(&stress_errors), result, recursive(3), (double)elapsed / 100000);
    int (*integer_point)(int) = (int (*)(int))dlsym(module, "easylsp_demo_intercept_value");
    double (*float_point)(double) = (double (*)(double))dlsym(module, "easylsp_demo_float_value");
    clock_gettime(CLOCK_MONOTONIC, &before);
    for (int i = 0; i < 100000; i++) result = integer_point(7);
    clock_gettime(CLOCK_MONOTONIC, &after);
    double integer_ns = ((after.tv_sec - before.tv_sec) * 1000000000LL + after.tv_nsec - before.tv_nsec) / 100000.0;
    clock_gettime(CLOCK_MONOTONIC, &before);
    volatile double float_result = 0;
    for (int i = 0; i < 100000; i++) float_result = float_point(2.0);
    clock_gettime(CLOCK_MONOTONIC, &after);
    double float_ns = ((after.tv_sec - before.tv_sec) * 1000000000LL + after.tv_nsec - before.tv_nsec) / 100000.0;
    __android_log_print(ANDROID_LOG_INFO, "EasylspDemoNative", "M4 intercept integer=%d float=%.1f integerNs=%.1f floatNs=%.1f", result, float_result, integer_ns, float_ns);
    dlclose(module);
}
