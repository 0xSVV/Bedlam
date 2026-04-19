import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ru.shapovalov.hysteria"
    compileSdk = libs.versions.targetSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    compileOnly(fileTree("libs") { include("*.aar") })
}

val golibDir = layout.projectDirectory.dir("golib")
val golibAar = layout.projectDirectory.file("libs/golib.aar")

fun findExecutable(name: String, extraPaths: List<String> = emptyList()): String? {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val names = if (isWindows) listOf("$name.exe", "$name.bat", "$name.cmd", name) else listOf(name)
    val pathDirs = (extraPaths + (System.getenv("PATH")?.split(File.pathSeparator) ?: emptyList()))
    return pathDirs
        .flatMap { dir -> names.map { File(dir, it) } }
        .firstOrNull { it.exists() && it.canExecute() }
        ?.absolutePath
}

fun findNdk(): String {
    System.getenv("ANDROID_NDK_HOME")?.let { ndk ->
        if (File(ndk).isDirectory) return ndk
    }

    val sdkDir = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: sdkDirFromLocalProperties()
        ?: error(
            "Android SDK not found. Set ANDROID_HOME environment variable " +
            "or sdk.dir in local.properties"
        )

    val ndkDir = File(sdkDir, "ndk")
    if (!ndkDir.isDirectory) {
        error(
            "Android NDK not found at $ndkDir\n" +
            "Install it via: Android Studio → Settings → SDK Tools → NDK (Side by side)\n" +
            "Or: sdkmanager --install \"ndk;30.0.14904198\""
        )
    }

    return ndkDir.listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?.absolutePath
        ?: error("NDK directory is empty at $ndkDir")
}

fun sdkDirFromLocalProperties(): String? {
    val file = rootProject.file("local.properties")
    if (!file.exists()) return null
    val props = Properties()
    file.reader().use { props.load(it) }
    return props.getProperty("sdk.dir")
}

fun findGoPath(): String {
    return System.getenv("GOPATH")
        ?: "${System.getProperty("user.home")}/go"
}

val buildGolib by tasks.registering(Exec::class) {
    description = "Build Go mobile bindings (.aar) via gomobile bind"
    group = "build"

    workingDir = golibDir.asFile
    inputs.dir(golibDir)
    outputs.file(golibAar)

    doFirst {
        val goPath = findGoPath()
        val goExe = findExecutable("go", listOf("$goPath/bin", "/usr/local/go/bin", "/opt/homebrew/bin"))
            ?: error(
                "Go is not installed or not in PATH.\n" +
                "Install it from: https://go.dev/dl/"
            )

        val gomobileExe = findExecutable("gomobile", listOf("$goPath/bin"))
            ?: error(
                "gomobile is not installed.\n" +
                "Install it with:\n" +
                "  go install golang.org/x/mobile/cmd/gomobile@latest\n" +
                "  go install golang.org/x/mobile/cmd/gobind@latest"
            )

        findExecutable("gobind", listOf("$goPath/bin"))
            ?: error(
                "gobind is not installed.\n" +
                "Install it with: go install golang.org/x/mobile/cmd/gobind@latest"
            )

        val submoduleDir = golibDir.asFile.resolve("../upstream/core")
        if (!submoduleDir.exists()) {
            error(
                "Hysteria submodule not found at ${submoduleDir.canonicalPath}\n" +
                "Initialize it with: git submodule update --init --recursive"
            )
        }

        val ndkPath = findNdk()
        val sdkPath = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: sdkDirFromLocalProperties()
            ?: ""

        environment("ANDROID_HOME", sdkPath)
        environment("ANDROID_NDK_HOME", ndkPath)
        environment("PATH", listOf(
            File(goExe).parent,
            File(gomobileExe).parent,
            System.getenv("PATH")
        ).joinToString(File.pathSeparator))

        commandLine(
            gomobileExe, "bind",
            "-target=android/arm64",
            "-androidapi", "29",
            "-tags", "with_gvisor",
            "-o", golibAar.asFile.absolutePath,
            "."
        )
    }
}

tasks.named("preBuild") {
    dependsOn(buildGolib)
}

tasks.named("clean") {
    doLast {
        delete("libs/golib.aar", "libs/golib-sources.jar")
    }
}
