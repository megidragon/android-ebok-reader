plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

}

android {
    namespace = "com.lectora.engine.tts.neural"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":engine:tts"))
    implementation(libs.coroutines.android)
    // Runtime de inferencia: sherpa-onnx (ONNX Runtime + Piper/VITS + Kokoro).
    implementation(project(":third-party:sherpa-onnx"))
    // Descomprimir los paquetes de voces (.tar.bz2).
    implementation(libs.commons.compress)
}
