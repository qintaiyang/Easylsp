plugins { alias(libs.plugins.android.library) }
android {
    namespace = "io.github.easylsp.api"
    compileSdk = 35
    defaultConfig { minSdk = 31 }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { testImplementation(libs.junit) }
