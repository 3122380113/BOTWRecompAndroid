plugins {
    id("com.android.application")
}

val repo: File = rootDir.resolve("../..").canonicalFile
val deps: File = repo.resolve("android/deps/out")  // android/deps/build_deps.sh
val gen = layout.buildDirectory.dir("generated/lasrecomp")

android {
    namespace = "com.lasrecomp"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.lasrecomp.android"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-dev"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DLAS_GEN_DIR=${gen.get().asFile.resolve("inc").absolutePath.replace('\\', '/')}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.4"
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", deps.resolve("java"))  // (SDL's Java side, the same version as libSDL3.so)
            jniLibs.srcDirs(gen.get().asFile.resolve("jniLibs"))
            assets.srcDirs(gen.get().asFile.resolve("assets"))
            res.srcDirs("src/main/res", gen.get().asFile.resolve("res"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true  // adrenotools loads its hooks from nativeLibraryDir
            pickFirsts += listOf("**/libSDL3.so", "**/libshaderc_shared.so", "**/libavcodec.so", "**/libavformat.so",
                                 "**/libavutil.so", "**/libswscale.so")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")  // (development builds only, for now)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// What the native build and the APK need from the repository: the SMAA shader as a C string (as the Makefile makes
// smaa_hlsl.inc), the prebuilt libraries, and the shipped resources (the start screen's font, the icon).
val prepareLasRecomp by tasks.registering {
    val smaa = repo.resolve("third_party/smaa/SMAA.hlsl")
    val font = repo.resolve("third_party/fonts/Baloo2/Baloo2-SemiBold.ttf")
    val icon = repo.resolve("docs/icon/icon_1024.png")
    val libs = listOf("libSDL3.so", "libshaderc_shared.so", "libavcodec.so", "libavformat.so", "libavutil.so", "libswscale.so")
    inputs.files(smaa, font, icon)
    inputs.files(libs.map { deps.resolve("lib/$it") }.filter { it.exists() })
    outputs.dir(gen)
    doLast {
        val out = gen.get().asFile
        // CR dropped, non-ASCII bytes as '?', backslashes and quotes escaped, every line a "...\n" literal
        val bytes = smaa.readBytes().filter { it != '\r'.code.toByte() }.map { if (it < 0) '?'.code.toByte() else it }
        val lines = String(bytes.toByteArray(), Charsets.US_ASCII).split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
        val inc = lines.joinToString("") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\\n\"\n" }
        out.resolve("inc").mkdirs()
        out.resolve("inc/smaa_hlsl.inc").writeText(inc)
        for (lib in libs)  // (FFmpeg's only once android/deps/build_ffmpeg.sh made them)
            if (deps.resolve("lib/$lib").exists()) deps.resolve("lib/$lib").copyTo(out.resolve("jniLibs/arm64-v8a/$lib"), overwrite = true)
        font.copyTo(out.resolve("assets/res/Baloo2-SemiBold.ttf"), overwrite = true)
        deps.resolve("fonts/DejaVuSans.ttf").copyTo(out.resolve("assets/res/DejaVuSans.ttf"), overwrite = true)
        icon.copyTo(out.resolve("assets/res/icon.png"), overwrite = true)
        icon.copyTo(out.resolve("res/mipmap-xxxhdpi/ic_launcher.png"), overwrite = true)
    }
}
tasks.named("preBuild") { dependsOn(prepareLasRecomp) }
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(prepareLasRecomp)
}
