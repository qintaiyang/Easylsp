#include <jni.h>
#include <signal.h>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>

__attribute__((noinline)) void easylsp_demo_fault(void) {
    *(volatile int *)(uintptr_t)0x10 = 42;
}
#ifdef FAULT_CONSTRUCTOR
__attribute__((constructor)) static void before_load_returns(void) { easylsp_demo_fault(); }
#endif
JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
#ifdef FAULT_JNI
    easylsp_demo_fault();
#endif
    return JNI_VERSION_1_6;
}
JNIEXPORT void JNICALL Java_io_github_easylsp_demo_DemoApplication_trigger(JNIEnv *env, jclass cls, jint mode) {
    switch (mode) {
        case 1: easylsp_demo_fault(); break;
        case 2: abort(); break;
        case 3: _exit(42);
        case 4: kill(getpid(), SIGKILL); break;
    }
}
