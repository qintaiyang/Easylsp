plugins { alias(libs.plugins.android.application) }
android {
    namespace = "io.github.easylsp.demo"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    externalNativeBuild { ndkBuild { path = file("src/main/native/Android.mk") } }
    defaultConfig {
        applicationId = "io.github.easylsp.demo"
        minSdk = 31
        ndk { abiFilters += "arm64-v8a" }
        targetSdk = 35
        versionCode = providers.gradleProperty("demoVersionCode").orElse("1").get().toInt()
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation(libs.junit); androidTestImplementation(libs.androidx.test.runner); androidTestImplementation(libs.androidx.test.junit) }
