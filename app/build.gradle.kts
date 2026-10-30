plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
}

// First 4 characters of the commit being built, appended to versionName.
val gitCommit: String = try {
    ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(rootDir)
        .start()
        .inputStream.bufferedReader().readText().trim().take(4)
} catch (e: Exception) {
    ""
}

android {
    namespace = "com.dividetask.audiobookreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dividetask.audiobookreader"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.currentTimeMillis() / 60000).toInt()
        versionName = "v0.204 a" + if (gitCommit.isNotEmpty()) " ($gitCommit)" else ""

        buildConfigField("long", "BUILD_TIMESTAMP", "${System.currentTimeMillis()}L")
    }

    // Shared signing key committed to the repo so every build (on any machine)
    // produces APKs with the same certificate. Without this, each build
    // environment generates a fresh debug keystore, which stops Android from
    // installing updates in place. Low-sensitivity key for a hobby project.
    signingConfigs {
        create("shared") {
            storeFile = file("audiobookreader.keystore")
            storePassword = "audiobookreader"
            keyAlias = "audiobookreader"
            keyPassword = "audiobookreader"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
            // The build script ships debug builds, so shrink them too (keep rules in proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("shared")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // Store native libraries (FFmpeg) compressed: a much smaller APK; Android
        // extracts them at install time
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.54")
    ksp("com.google.dagger:hilt-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Networking (Audible API)
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    // Same OkHttp as Ktor's engine; used directly for the Audible request log interceptor
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // FFmpeg for AAX to M4B conversion (community-maintained fork)
    implementation("dev.ffmpegkit-maintained:ffmpeg:8.1.7")

    // Encrypted SharedPreferences for token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // WebView for Audible login
    implementation("androidx.webkit:webkit:1.12.1")

    // Background work (server backup)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // SFTP client (maintained JSch fork)
    implementation("com.github.mwiede:jsch:0.2.20")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
