plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codex.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.mobile"
        minSdk = 24
        // targetSdk 28 allows executing binaries from app data directory.
        // Android 10+ (targetSdk 29+) enforces W^X which blocks this via SELinux.
        // Termux (F-Droid) uses the same approach.
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        // Release APK is signed so it can be sideloaded on any device.
        //
        // CI path: the workflow decodes ANDROID_KEYSTORE_BASE64 to a file and
        //   passes ANDROID_KEYSTORE_PATH, ANDROID_KEY_ALIAS, ANDROID_STORE_PASSWORD,
        //   ANDROID_KEY_PASSWORD as Gradle project properties (-P flags).
        //
        // Local / fallback path: uses the built-in SDK debug keystore so
        //   `./gradlew assembleRelease` always produces a signed APK without extra setup.
        create("release") {
            val ksPath        = project.findProperty("ANDROID_KEYSTORE_PATH")   as String?
            val keyAlias      = project.findProperty("ANDROID_KEY_ALIAS")       as String?
            val storePassword = project.findProperty("ANDROID_STORE_PASSWORD")  as String?
            val keyPassword   = project.findProperty("ANDROID_KEY_PASSWORD")    as String?

            if (ksPath != null && keyAlias != null && storePassword != null && keyPassword != null) {
                storeFile          = file(ksPath)
                this.storePassword = storePassword
                this.keyAlias      = keyAlias
                this.keyPassword   = keyPassword
            } else {
                // Fall back to the SDK debug keystore for local unsigned builds
                storeFile     = signingConfigs.getByName("debug").storeFile
                storePassword = signingConfigs.getByName("debug").storePassword
                keyAlias      = signingConfigs.getByName("debug").keyAlias
                keyPassword   = signingConfigs.getByName("debug").keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Don't compress bootstrap zip or server bundle in assets
    androidResources {
        noCompress += listOf("zip", "tar.gz")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.android.material:material:1.12.0")
}
