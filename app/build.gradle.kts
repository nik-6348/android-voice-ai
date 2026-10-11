plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nik.voiceassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nik.voiceassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // Vosk / JNA ship native libraries for every CPU; phones (and the arm emulator) only need 64-bit ARM
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "DEFAULT_WS_URL", "\"wss://ai.nik6348.in/ws/live\"")
        buildConfigField("String", "DEFAULT_API_KEY", "\"Shiv+Shakti=Love@143\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Networking & WebSocket
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Concurrency
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // JSON Parsing
    implementation(libs.gson)

    // Offline "Hi Nik" wake word (Vosk speech recognition; model in assets/model-en-in)
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }

    debugImplementation(libs.androidx.ui.tooling)
}
