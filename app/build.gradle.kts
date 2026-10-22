plugins {
    id("com.android.application")
}

// CI passes the run number so every build can be installed over the previous one.
val buildNumber = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "io.github.hpjgyfryfuj.geminiexporter"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.hpjgyfryfuj.geminiexporter"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // The key lives in the repo so every CI build is signed the same way
            // and updates install over each other. It guards nothing secret.
            storeFile = file("release.jks")
            storePassword = "gemini-exporter"
            keyAlias = "gemini-exporter"
            keyPassword = "gemini-exporter"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
    }
}
