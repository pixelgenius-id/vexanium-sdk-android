plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vexanium.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.vexanium.sample"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":vexanium"))
    implementation(libs.kotlinx.coroutines.core)
}
