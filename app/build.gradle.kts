import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing lives outside the build file so the password never ends up in
// a repo. Falls back to the debug key when the file is absent, so a fresh clone
// still builds — it just cannot produce an installable update.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Where the likes go. Kept out of the tree for the same reason as the signing
// key, though not for the same reason of secrecy: the anon key is public by
// design — it ships inside the APK, so anyone who wants it has it — and what is
// actually being kept out of the repo is the project URL, so that a fork builds
// against nothing rather than quietly writing into this project's table.
//
// Absent is a supported state. The build still compiles, the fields are empty,
// and the hearts do not appear at all. See social/Likes.kt.
val supabaseProperties = Properties().apply {
    val f = rootProject.file("supabase.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.ishaan.essentialvoice"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.ishaan.essentialvoice"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "3.0"

        // Where the app looks for news of a newer build. See Updater.kt.
        buildConfigField(
            "String",
            "UPDATE_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/email2ishaanpatel-collab/essential-voice/main/update.json\"",
        )

        // The likes backend: a PostgREST base and the public anon key. Empty
        // when supabase.properties is missing, which switches the feature off
        // rather than failing — see social/Likes.kt.
        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${supabaseProperties.getProperty("url", "").trimEnd('/')}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"${supabaseProperties.getProperty("anonKey", "")}\"",
        )

        // Only ABI this phone needs. Keeps the APK ~4MB instead of ~30MB.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_static") }
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                // v3 is what makes rotating this key possible later without
                // every install having to be removed by hand.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Without R8 the dex is 17.7MB of unreached Compose.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isJniDebuggable = false

            // Signed with the release key, when there is one. Android refuses an
            // update signed with a different key, so a debug-signed test build
            // can only be installed by uninstalling first — which throws away
            // the learned key, the settings and the ~150MB model every time.
            // Same key means a test build drops straight on top of a release.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")

            // So the version shown in the app says which build this is. There is
            // no other visible difference between a test build and a release.
            versionNameSuffix = "-test"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
