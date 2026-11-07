plugins { base }
val hostZip = layout.buildDirectory.file("outputs/easylsp-native-host-0.9.3.zip")
val buildHost = tasks.register<Exec>("assembleHost") {
    dependsOn(":native-runtime:mergeDebugNativeLibs")
    inputs.files(fileTree("src"), fileTree("third_party"), fileTree("module"), file("build-host.py"))
    inputs.files(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_NOTICES.md"), rootProject.file("app/src/main/assets/third-party/shadowhook/LICENSE"))
    inputs.dir(project(":native-runtime").layout.buildDirectory.dir("intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a"))
    outputs.file(hostZip)
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"
    commandLine("python3", file("build-host.py"), "--ndk", "$sdk/ndk/28.2.13676358", "--runtime",
        project(":native-runtime").layout.buildDirectory.dir("intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a").get().asFile,
        "--output", hostZip.get().asFile)
}
tasks.named("assemble") { dependsOn(buildHost) }
