plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

fun String.toBuildConfigString(): String {
    return "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun readConfig(name: String): String {
    return providers.environmentVariable(name)
        .orElse(providers.gradleProperty(name))
        .orElse("")
        .get()
}

val llmEndpoint = readConfig("LLM_ENDPOINT")
val llmApiKey = readConfig("OPENAI_API_KEY")
val whisperEndpoint = readConfig("WHISPER_ENDPOINT")
val ttsEndpoint = readConfig("TTS_ENDPOINT")

android {
    namespace = "com.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LLM_ENDPOINT", llmEndpoint.toBuildConfigString())
        buildConfigField("String", "LLM_API_KEY", llmApiKey.toBuildConfigString())
        buildConfigField("String", "WHISPER_ENDPOINT", whisperEndpoint.toBuildConfigString())
        buildConfigField("String", "TTS_ENDPOINT", ttsEndpoint.toBuildConfigString())
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
        buildConfig = true
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
