import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ndkVer = "28.2.13676358"
val abis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "com.infinitum.aether"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = ndkVer

    defaultConfig {
        applicationId = "com.infinitum.aether"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += abis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/uniffi/kotlin"))
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/uniffi/jniLibs"))

    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// ---------------------------------------------------------------------------
// Rust security core: cross-compile with cargo-ndk, then generate Kotlin
// bindings with UniFFI (library mode, reading metadata from the built .so).
// ---------------------------------------------------------------------------
val coreDir = rootProject.projectDir.resolve("../core").canonicalFile
val jniOut = layout.buildDirectory.dir("generated/uniffi/jniLibs").get().asFile
val ktOut = layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile
val cargoHome = File(System.getProperty("user.home"), ".cargo/bin")
val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
val cargo = cargoHome.resolve("cargo$exe").takeIf { it.exists() }?.path ?: "cargo"
// On Windows the GNU Rust host needs MinGW-w64 (dlltool/as) to build uniffi-bindgen.
// Set AETHER_MINGW_BIN, or install WinLibs to ~/tools/winlibs.
val mingwBin = (System.getenv("AETHER_MINGW_BIN") ?: File(System.getProperty("user.home"), "tools/winlibs/mingw64/bin").path)
    .let(::File).takeIf { it.isDirectory }
val toolPath = listOfNotNull(cargoHome, mingwBin).joinToString(File.pathSeparator) { it.path } +
    File.pathSeparator + System.getenv("PATH")
val sdkDir = System.getenv("ANDROID_HOME")
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.replace("\\:", ":")?.replace("\\\\", "\\")
    ?: "${System.getenv("LOCALAPPDATA")}/Android/Sdk"

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "aether"
    description = "Cross-compiles the ÆTHER Rust core for Android."
    workingDir = coreDir
    environment("ANDROID_NDK_HOME", "$sdkDir/ndk/$ndkVer")
    environment("PATH", toolPath)
    commandLine(listOf(cargo, "ndk") + abis.flatMap { listOf("-t", it) } +
        listOf("-P", "28", "-o", jniOut.path, "build", "--release", "--lib"))
    inputs.dir(coreDir.resolve("src"))
    inputs.file(coreDir.resolve("Cargo.toml"))
    outputs.dir(jniOut)
}

// UniFFI reads its metadata from library symbols, which the release profile
// strips from the Android .so — so bindings come from an unstripped host build.
val os = System.getProperty("os.name")
val hostLib = coreDir.resolve("target/debug/" + when {
    os.startsWith("Windows") -> "aether_core.dll"
    os.startsWith("Mac") -> "libaether_core.dylib"
    else -> "libaether_core.so"
})

val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    group = "aether"
    description = "Builds the ÆTHER Rust core for the host (bindings metadata)."
    workingDir = coreDir
    environment("PATH", toolPath)
    commandLine(cargo, "build", "--quiet", "--lib")
    inputs.dir(coreDir.resolve("src"))
    inputs.file(coreDir.resolve("Cargo.toml"))
    outputs.file(hostLib)
}

val uniffiBindgen = tasks.register<Exec>("uniffiBindgen") {
    group = "aether"
    description = "Generates Kotlin bindings for the ÆTHER Rust core."
    dependsOn(cargoNdkBuild, cargoHostBuild)
    workingDir = coreDir
    environment("PATH", toolPath)
    commandLine(cargo, "run", "--quiet", "--bin", "uniffi-bindgen", "--", "generate",
        "--library", hostLib.path,
        "--language", "kotlin", "--out-dir", ktOut.path, "--no-format")
    inputs.file(hostLib)
    outputs.dir(ktOut)
    doLast {
        check(ktOut.walkTopDown().any { it.extension == "kt" }) { "uniffi-bindgen produced no Kotlin sources" }
    }
}

tasks.named("preBuild") { dependsOn(uniffiBindgen) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
}
