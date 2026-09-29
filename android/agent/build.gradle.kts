import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.antigravity.agent"
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "app.antigravity.agent"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        resourceConfigurations += "en"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("AGM_RELEASE_STORE_FILE") as String?
            if (storeFilePath != null) {
                val resolved = File(rootProject.projectDir, storeFilePath)
                if (resolved.exists()) {
                    storeFile = resolved
                    storePassword = project.findProperty("AGM_RELEASE_STORE_PASSWORD") as String?
                    keyAlias = project.findProperty("AGM_RELEASE_KEY_ALIAS") as String?
                    keyPassword = project.findProperty("AGM_RELEASE_KEY_PASSWORD") as String?
                }
            }
        }
    }

    buildTypes {
        release {
            // Kept off on purpose: no reflection-heavy libraries, and a smaller risk of shrinker surprises.
            isMinifyEnabled = false
            val rc = signingConfigs.getByName("release")
            if (rc.storeFile != null) signingConfig = rc
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { disable += "Instantiatable" }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(project(":agent-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
}
