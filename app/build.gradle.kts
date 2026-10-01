plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.budgettracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.budgettracker"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // pdfbox ships its own Bouncy Castle; exclude duplicate signing entries
            excludes += "/META-INF/BCKEY.DSA"
            excludes += "/META-INF/BCKEY.SF"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Only the small core icon set; extra icons live in ui/icons
    implementation("androidx.compose.material:material-icons-core")

    // Room for local persistence
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // GPS location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // osmdroid map (no API key required)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Coil for async image loading (shop logos)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // MLKit OCR — for parsing PNG receipts
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // pdfbox-android — for extracting text from PDF receipts
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // JVM unit tests — no Android emulator needed for pure-Kotlin receipt parsing logic
    testImplementation("junit:junit:4.13.2")
    // org.json is provided by the Android platform at runtime but must be added explicitly for JVM tests
    testImplementation("org.json:json:20231013")

    // Instrumented tests — full pipeline including MLKit OCR, run on device/emulator
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
