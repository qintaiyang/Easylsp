plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.easylsp.standalone"
    compileSdk = 35
    defaultConfig { applicationId = "io.github.easylsp.generated.template"; minSdk = 34; targetSdk = 35; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":hook-runtime"))
    compileOnly(libs.xposed)
    compileOnly(libs.libxposed)
}
