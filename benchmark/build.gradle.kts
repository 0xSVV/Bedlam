plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "ru.shapovalov.bedlam.benchmarks"
    compileSdk = libs.versions.targetSdk.get().toInt()
    defaultConfig {
        minSdk = 29
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.ext)
    implementation(libs.androidx.test.uiautomator)
}

// AGP installs the target app's APKs here, not its transitive library artifacts.
configurations.matching { it.name.endsWith("TestedApks") }.configureEach {
    isTransitive = false
}
