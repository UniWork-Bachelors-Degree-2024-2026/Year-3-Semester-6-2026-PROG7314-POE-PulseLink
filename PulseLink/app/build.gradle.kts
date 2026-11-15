plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.prog7314_poepart23"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.prog7314_poepart23"
        minSdk = 25
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // RecyclerView adapters and item card layouts for all list screens (Google Developers, 2024)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Firebase BoM & services (Google Cloud, 2024)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Retrofit2 REST API Client & JSON Serialisation (Square, 2024)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")  // (Square, 2024)

    // Kotlin Coroutines for asynchronous background processing (JetBrains, 2024)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")  // (Google Developers, 2024)
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")   // (Google Developers, 2024)

    // Room Database for offline caching & telemetry persistence (Google Developers, 2024)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    // WorkManager for scheduled background synchronisation (Google Developers, 2024)
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Biometric authentication (Google Developers, 2024)
    implementation("androidx.biometric:biometric:1.1.0")

    // Security & Encrypted Preferences (Google Developers, 2024)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Skeleton shimmer loading placeholders (Facebook, 2024)
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}