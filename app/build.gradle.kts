plugins {
    id("com.android.application")
    id("kotlin-android")
}

android {
    namespace = "com.teleflix.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teleflix.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("teleflix") {
            storeFile = file("teleflix.keystore")
            storePassword = System.getenv("TELEFLIX_STORE_PASSWORD") ?: "teleflix123"
            keyAlias = System.getenv("TELEFLIX_KEY_ALIAS") ?: "teleflix"
            keyPassword = System.getenv("TELEFLIX_KEY_PASSWORD") ?: "teleflix123"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("teleflix")
        }
        release {
            signingConfig = signingConfigs.getByName("teleflix")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Networking & Image Loading
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
