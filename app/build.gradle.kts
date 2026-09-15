import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Legge le proprietà da local.properties
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(FileInputStream(localPropertiesFile))
    }
}

android {
    namespace = "com.example.localvoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.localvoice"
        minSdk = 29        // VoiceInteractionService stabile da qui in su
        targetSdk = 35
        versionCode = 2
        versionName = "lori-1.0.2"

        // GrapheneOS supporta SOLO dispositivi Pixel, tutti arm64 — bundlare
        // armeabi-v7a/x86/x86_64 gonfia l'APK senza alcun beneficio per il
        // tuo caso d'uso. Non incide sul consumo a runtime (è solo
        // dimensione del pacchetto), ma è uno spreco facile da evitare.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        val spotifyClientId = localProperties.getProperty("SPOTIFY_CLIENT_ID") ?: ""
        val spotifyClientSecret = localProperties.getProperty("SPOTIFY_CLIENT_SECRET") ?: ""
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
        buildConfigField("String", "SPOTIFY_CLIENT_SECRET", "\"$spotifyClientSecret\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    buildTypes {
        release {
            // Attenzione: NON testato da me (non posso compilare/eseguire
            // build di release in questo ambiente). Riduce dimensione APK e
            // rimuove codice morto/log di debug non referenziati, ma può
            // rompere silenziosamente librerie basate su reflection/JNI se
            // mancano keep rules adeguate — le ho scritte in
            // proguard-rules.pro per Vosk/ONNX Runtime/openWakeWord, ma
            // vanno verificate con un vero `./gradlew assembleRelease`
            // seguito da un test manuale sul dispositivo prima di fidarsi.
            // Se qualcosa si rompe SOLO in release (mai in debug), è quasi
            // sempre minify — rimetti isMinifyEnabled a false per confermare,
            // poi aggiusta le keep rules invece di abbandonare l'idea.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // I modelli ONNX (~3.6MB totali) vanno impacchettati senza compressione:
    // AAPT comprimerebbe file già compatti in modo inefficiente e ONNX Runtime
    // su alcuni dispositivi richiede accesso via mmap, che non funziona su
    // asset compressi.
    androidResources {
        noCompress += listOf("onnx")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    // STT — 100% offline, modello (~48MB) da scaricare a parte, vedi
    // VoskSttEngine.kt per le istruzioni.
    implementation("com.alphacephei:vosk-android:0.3.70")

    //============================== UI ====================================
    // Gestione centralizzata delle versioni di Compose (BOM) in Kotlin DSL
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Librerie Core e Material Design 3
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Necessario per usare setContent{} nella MainActivity e nei servizi
    implementation("androidx.activity:activity-compose:1.8.2")
    //======================================================================

    testImplementation("junit:junit:4.13.2")
}
