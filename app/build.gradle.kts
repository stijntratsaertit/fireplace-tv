plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Builds the manifest, tile banners and assets from config/scenes.json.
apply(from = "scenes.gradle.kts")

// The Google Home (Matter) plug, written in Rust (see /matter), built for the TV's 32-bit ARM CPU
// and packaged as libfireplace_matter.so so Android installs it next to the app.
val matterJniLibs = layout.buildDirectory.dir("generated/matter/jniLibs").get().asFile

val buildMatter by tasks.registering(Exec::class) {
    val crate = rootProject.file("matter")
    inputs.dir(crate.resolve("src"))
    inputs.file(crate.resolve("Cargo.toml"))
    outputs.dir(matterJniLibs)
    workingDir = crate
    commandLine("cargo", "build", "--release", "--target", "armv7-linux-androideabi")
    doFirst {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
                ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
        val ndk = listOfNotNull(System.getenv("ANDROID_NDK_HOME"), System.getenv("ANDROID_NDK_LATEST_HOME"))
            .map(::File).firstOrNull { it.isDirectory }
            ?: sdk?.let { File(it, "ndk").listFiles()?.filter { f -> f.isDirectory }?.maxByOrNull { f -> f.name } }
            ?: throw GradleException("Android NDK not found (set ANDROID_NDK_HOME)")
        val bin = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin")
        environment("CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER", File(bin, "armv7a-linux-androideabi26-clang").path)
        environment("CC_armv7_linux_androideabi", File(bin, "armv7a-linux-androideabi26-clang").path)
        environment("AR_armv7_linux_androideabi", File(bin, "llvm-ar").path)
    }
    doLast {
        val out = File(matterJniLibs, "armeabi-v7a").apply { mkdirs() }
        crate.resolve("target/armv7-linux-androideabi/release/fireplace-matter")
            .copyTo(File(out, "libfireplace_matter.so"), overwrite = true)
    }
}

tasks.named("preBuild") { dependsOn(buildMatter) }

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
        versionName = "2.1.$versionCode-matter"
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
        // The Matter plug is a program, not a library: it must be extracted to disk so it can run.
        jniLibs.useLegacyPackaging = true
    }

    sourceSets["main"].jniLibs.srcDir(matterJniLibs)
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
    // Draws the Google Home pairing QR code.
    implementation("com.google.zxing:core:3.5.3")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
