plugins { base }
val buildEntry = tasks.register<Exec>("assembleEntry") {
    dependsOn(":native-runtime:mergeDebugNativeLibs")
    inputs.files(file("build.py"), file("launcher.c"), file("bootstrap.c"))
    val runtime = project(":native-runtime").layout.buildDirectory.dir("intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a")
    inputs.dir(runtime)
    inputs.files(rootProject.file("native-runtime/src/main/cpp/include/easylsp_native_core.h"), rootProject.file("zygisk-host/third_party/cJSON.c"), rootProject.file("zygisk-host/third_party/cJSON.h"))
    outputs.dir(layout.buildDirectory.dir("outputs/arm64-v8a"))
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"
    commandLine("python3", file("build.py"), "--ndk", "$sdk/ndk/28.2.13676358", "--runtime", runtime.get().asFile, "--output", layout.buildDirectory.dir("outputs/arm64-v8a").get().asFile)
}
tasks.named("assemble") { dependsOn(buildEntry) }
