# ShadowHook 2.0.1

Pinned source: https://github.com/bytedance/android-inline-hook/tree/v2.0.1/shadowhook/src/main/cpp
MIT license retained in LICENSE and source headers.

Both APK and Zygisk host build this same C backend. The build excludes the unused
upstream Java JNI adapter and its JNI_OnLoad dependency. The version map omits
ARM32-only exports. shadowhook-helper-fd.patch adds a synchronous internal host
entry that first loads the initialization helper from a pre-opened FD; normal
APK initialization retains the upstream library-name loader. Public plugin v1
layout and exclusive semantics remain compatible.
