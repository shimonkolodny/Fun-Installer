plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.funinstaller.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.funinstaller.app"
        minSdk = 30 // Android 11: needed for accessibility screenshots
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed key committed to this private repo, so every build can update
        // the previous one without uninstalling (which would erase settings).
        getByName("debug") {
            storeFile = file("fun-installer.keystore")
            storePassword = "funinstaller"
            keyAlias = "funinstaller"
            keyPassword = "funinstaller"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/**", "META-INF/INDEX.LIST",
                "META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version", "**/module-info.class",
            )
        }
    }
}

dependencies {
    implementation("com.anthropic:anthropic-java:3.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
