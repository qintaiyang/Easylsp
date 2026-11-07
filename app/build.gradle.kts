import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
}
android {
    providers.gradleProperty("easylspDebugKeystore").orNull?.let { signingConfigs.getByName("debug").storeFile = file(it) }
    namespace = "io.github.easylsp.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.github.easylsp"
        minSdk = 31
        targetSdk = 35
        versionCode = 910
        versionName = "9.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        javaCompileOptions { annotationProcessorOptions { arguments["room.schemaLocation"] = "$projectDir/schemas" } }
    }
    val releaseSigningPath = providers.gradleProperty("easylspReleaseSigning").orNull
    if (releaseSigningPath != null) {
        val credentials = Properties().apply { file(releaseSigningPath).inputStream().use { load(it) } }
        signingConfigs.create("distribution") {
            storeFile = file(credentials.getProperty("storeFile"))
            storePassword = credentials.getProperty("storePassword")
            keyAlias = credentials.getProperty("keyAlias")
            keyPassword = credentials.getProperty("keyPassword")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("distribution")
    }
    testOptions { unitTests.all { it.systemProperty("easylsp.toolExample", rootProject.file("examples/tool-plugins/echo.java").absolutePath) } }
    sourceSets["main"].assets.srcDir("../examples/plugins")
    sourceSets["androidTest"].assets.srcDir("schemas")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}
dependencies {
    implementation(project(":native-runtime"))
    implementation("com.android.tools.build:apksig:8.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    implementation(project(":hook-runtime"))
    implementation(project(":terminal-component"))
    implementation(project(":plugin-api"))
    implementation(libs.libxposed.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)
    implementation(libs.datastore)
    implementation(libs.coroutines)
    implementation(libs.okhttp)
    implementation("org.yaml:snakeyaml:2.3")
    implementation(libs.beanshell)
    implementation(libs.smali.dexlib)
    implementation(libs.smali.baksmali)
    implementation(libs.dexkit)
    implementation(libs.jadx.core)
    implementation(libs.jadx.dex)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("io.github.libxposed:interface:101.0.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

val standaloneAssets = tasks.register<Copy>("prepareStandaloneTemplate") {
    dependsOn(":standalone-template:assembleDebug")
    from(project(":standalone-template").layout.buildDirectory.file("outputs/apk/debug/standalone-template-debug.apk"))
    into(layout.buildDirectory.dir("generated/standaloneAssets"))
    rename { "standalone-template.apk" }
}
android.sourceSets["main"].assets.srcDir(files(layout.buildDirectory.dir("generated/standaloneAssets")).builtBy(standaloneAssets))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(standaloneAssets) }
tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    dependsOn(standaloneAssets)
    systemProperty("easylsp.standaloneTemplate", project(":standalone-template").layout.buildDirectory.file("outputs/apk/debug/standalone-template-debug.apk").get().asFile.absolutePath)
    systemProperty("easylsp.debugKeystore", android.signingConfigs.getByName("debug").storeFile!!.absolutePath)
    systemProperty("easylsp.packagingResult", layout.buildDirectory.dir("p3-verification").get().asFile.absolutePath)
}

// A root child isolates the mount namespace with syscalls before running any chroot setup.
val namespaceAssets = layout.buildDirectory.dir("generated/namespaceApi31Assets")
val buildNamespaceLauncher = tasks.register<Exec>("buildNamespaceLauncher") {
    val source = file("src/main/native")
    inputs.files(fileTree(source), rootProject.file("native-runtime/src/main/cpp/diagnostic_wire.h"))
    outputs.file(namespaceAssets.map { it.file("namespace/arm64-v8a/easylsp-diagnostic-collector") })
    outputs.file(namespaceAssets.map { it.file("namespace/arm64-v8a/easylsp-namespace") })
    commandLine(androidComponents.sdkComponents.ndkDirectory.get().file("ndk-build").asFile,
        "NDK_PROJECT_PATH=null", "APP_BUILD_SCRIPT=${source.resolve("Android.mk")}",
        "APP_PLATFORM=android-31", "APP_ABI=arm64-v8a", "APP_STL=none",
        "NDK_OUT=${layout.buildDirectory.dir("namespaceApi31Obj").get().asFile}",
        "NDK_LIBS_OUT=${namespaceAssets.get().asFile.resolve("namespace")}")
}
android.sourceSets["main"].assets.srcDir(files(namespaceAssets).builtBy(buildNamespaceLauncher))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(buildNamespaceLauncher) }

val harnessAssets = tasks.register<Copy>("prepareHarnessAssets") {
    from(rootProject.file("harness")) {
        include("package.json", "package-lock.json", "runtime.mjs", "probe.mjs", "session-repair.mjs", "model-http.mjs", "model-profile.mjs", "control.mjs", "android-policy.mjs", "android-mcp.mjs", "bridge-control.mjs", "extensions.mjs", "host.mjs", "boot.mjs", "daemon.mjs")
    }
    into(layout.buildDirectory.dir("generated/harnessAssets/harness"))
}
val capabilityAssets = tasks.register<Copy>("prepareCapabilityAssets") {
    from(rootProject.file("examples/skills/presets")) { into("skill-presets") }
    from(rootProject.file("examples/skills/tool-groups.json"))
    into(layout.buildDirectory.dir("generated/capabilityAssets"))
}
android.sourceSets["main"].assets.srcDir(files(layout.buildDirectory.dir("generated/capabilityAssets")).builtBy(capabilityAssets))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(capabilityAssets) }
android.sourceSets["main"].assets.srcDir(files(layout.buildDirectory.dir("generated/harnessAssets")).builtBy(harnessAssets))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(harnessAssets) }

// Ships only Android target files; clang/lld run from the phone's Ubuntu arm64 packages.
val nativeSdkAssets = layout.buildDirectory.dir("generated/nativeSdkApi31Assets")
val packageNativeSdk = tasks.register<Exec>("packageNativeSdk") {
    inputs.files(rootProject.file("scripts/package-native-sdk.py"), rootProject.fileTree("scripts/native-sdk"))
    inputs.file(androidComponents.sdkComponents.ndkDirectory.get().file("source.properties"))
    outputs.file(nativeSdkAssets.map { it.file("native-sdk/android31-r28c.tar") })
    commandLine("python3", rootProject.file("scripts/package-native-sdk.py"),
        "--ndk", androidComponents.sdkComponents.ndkDirectory.get().asFile,
        "--output", nativeSdkAssets.get().file("native-sdk/android31-r28c.tar").asFile)
}
val nativeSdkHeader = tasks.register<Copy>("prepareNativeSdkHeader") {
    from(rootProject.file("native-runtime/src/main/cpp/include/easylsp_native_plugin.h"))
    into(nativeSdkAssets.map { it.dir("native-sdk") })
}
android.sourceSets["main"].assets.srcDir(files(nativeSdkAssets).builtBy(packageNativeSdk, nativeSdkHeader))
android.sourceSets["main"].assets.srcDir(rootProject.file("examples/native-plugins"))
android.sourceSets["androidTest"].assets.srcDir(rootProject.file("examples/native-plugins"))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(packageNativeSdk, nativeSdkHeader)
}

val hostAssets = tasks.register<Copy>("prepareNativeHostAssets") {
    dependsOn(":zygisk-host:assembleHost")
    from(rootProject.file("zygisk-host/build/outputs/easylsp-native-host-9.1.zip"))
    rename { "easylsp-native-host.zip" }
    into(layout.buildDirectory.dir("generated/nativeHostAssets"))
}
android.sourceSets["main"].assets.srcDir(files(layout.buildDirectory.dir("generated/nativeHostAssets")).builtBy(hostAssets))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(hostAssets) }

// Fixed desktop-built fixtures keep device acceptance independent of phone compilation.
val nativeAcceptanceAssets = layout.buildDirectory.dir("generated/nativeAcceptanceAssets")
val prepareNativeAcceptance = tasks.register<Exec>("prepareNativeAcceptance") {
    dependsOn(":demo-target:assembleDebug")
    inputs.files(rootProject.fileTree("examples/native-plugins"), rootProject.file("scripts/prepare-native-candidates.py"), rootProject.file("native-runtime/src/main/cpp/include/easylsp_native_plugin.h"))
    val demoLibrary = project(":demo-target").layout.buildDirectory.file("intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libdemo_early_hook.so")
    inputs.file(demoLibrary)
    inputs.file(androidComponents.sdkComponents.ndkDirectory.get().file("source.properties"))
    outputs.dir(nativeAcceptanceAssets)
    commandLine("python3", rootProject.file("scripts/prepare-native-candidates.py"),
        "--ndk", androidComponents.sdkComponents.ndkDirectory.get().asFile,
        "--demo-lib", demoLibrary.get().asFile, "--output", nativeAcceptanceAssets.get().dir("fixed-native").asFile)
}
android.sourceSets["androidTest"].assets.srcDir(files(nativeAcceptanceAssets).builtBy(prepareNativeAcceptance))
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(prepareNativeAcceptance) }

// AGP lint model tasks read the asset directories without retaining srcDir builtBy metadata.
tasks.matching { it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach {
    dependsOn(standaloneAssets, buildNamespaceLauncher, harnessAssets, capabilityAssets,
        packageNativeSdk, nativeSdkHeader, hostAssets, prepareNativeAcceptance)
}

// Ship the UID0 keeper and its ColorOS Hans helper with the manager.
val keepaliveAssets = layout.buildDirectory.dir("generated/keepaliveAssets")
val prepareKeepalive = tasks.register<Exec>("prepareManagerKeepalive") {
    inputs.files(rootProject.file("scripts/easylsp-manager-keepalive.sh"), rootProject.file("scripts/easylsp-manager-unfreeze.c"))
    outputs.dir(keepaliveAssets)
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: "/opt/android-sdk"
    doFirst {
        val output = keepaliveAssets.get().dir("manager-keepalive").asFile.apply { mkdirs() }
        rootProject.file("scripts/easylsp-manager-keepalive.sh").copyTo(output.resolve("service.sh"), overwrite = true)
    }
    commandLine("$sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android31-clang", "-std=c17", "-O2", "-Wall", "-Wextra", "-Werror", "-fPIE", "-pie", "-Wl,-z,max-page-size=16384", rootProject.file("scripts/easylsp-manager-unfreeze.c"), "-o", keepaliveAssets.get().file("manager-keepalive/unfreeze-uid").asFile)
}
android.sourceSets["main"].assets.srcDir(files(keepaliveAssets).builtBy(prepareKeepalive))
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") || it.name.contains("Lint") || it.name.startsWith("lint") }.configureEach { dependsOn(prepareKeepalive) }
