plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Builds the manifest, tile banners and assets from config/scenes.json.
apply(from = "scenes.gradle.kts")

android {
    namespace = "app.fireplace"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.fireplace"
        minSdk = 26
        targetSdk = 36
        // GitHub Actions sets GITHUB_RUN_NUMBER, so every build installs over the previous one.
        // Starts at 100 so it also installs over the earlier SmartTube-launcher version.
        versionCode = 100 + (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionName = "2.0.$versionCode"
    }

    signingConfigs {
        // A fixed key, committed on purpose, so each new APK can be installed over the old one.
        // It only signs this personal sideloaded app; it protects nothing else.
        create("sideload") {
            storeFile = rootProject.file("sideload.keystore")
            storePassword = "fireplace"
            keyAlias = "fireplace"
            keyPassword = "fireplace"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    sourceSets["main"].apply {
        manifest.srcFile(project.extra["scenesManifest"] as File)
        res.srcDir(project.extra["scenesRes"] as File)
        assets.srcDir(project.extra["scenesAssets"] as File)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses newer Java APIs; desugaring makes them work on every Android version.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        // Lint's release check crashes on NewPipeExtractor's classes; it isn't needed for a sideloaded app.
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-session:$media3")
    // Finds and resolves YouTube videos without the YouTube app (the library NewPipe uses).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
