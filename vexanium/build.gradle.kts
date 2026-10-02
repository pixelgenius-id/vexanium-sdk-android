plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.vexanium.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    api(libs.bouncycastle)
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.vexanium"
                artifactId = "sdk"
                version = "0.2.0"
            }
        }
    }
}
