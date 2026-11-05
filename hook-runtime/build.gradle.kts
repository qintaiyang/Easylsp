plugins { alias(libs.plugins.android.library) }
android {
    namespace = "io.github.easylsp.runtime"
    compileSdk = 35
    defaultConfig { minSdk = 34 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.all { it.systemProperty("easylsp.examples", rootProject.file("examples/plugins").absolutePath) }
    }
}
dependencies {
    compileOnly(project(":native-runtime"))
    api(project(":plugin-api"))
    implementation(libs.beanshell)
    compileOnly(libs.xposed)
    compileOnly(libs.libxposed)
    testImplementation(libs.libxposed)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
