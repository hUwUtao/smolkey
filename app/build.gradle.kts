import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "work.stdpi.smolkey"
    compileSdk = 37
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "work.stdpi.smolkey"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

abstract class BuildEnsemble @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val sources: DirectoryProperty

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustInputs: ConfigurableFileCollection

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:LocalState
    abstract val cargoTarget: DirectoryProperty

    @get:OutputDirectory
    abstract val jniLibs: DirectoryProperty

    @TaskAction
    fun build() {
        execOperations.exec {
            workingDir(sources.get().asFile)
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile)
            environment("CARGO_TARGET_DIR", cargoTarget.get().asFile)
            commandLine(
                "cargo", "ndk", "-P", "31",
                "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64", "-t", "x86",
                "-o", jniLibs.get().asFile.absolutePath,
                "build", "--release", "--locked",
            )
        }
    }
}

val buildEnsemble = tasks.register<BuildEnsemble>("buildEnsemble") {
    sources.set(rootProject.layout.projectDirectory.dir("native"))
    rustInputs.from(sources.asFileTree.matching {
        include("Cargo.toml", "Cargo.lock", "build.rs", "src/**")
    })
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    cargoTarget.set(layout.buildDirectory.dir("ensemble/cargo"))
    jniLibs.set(layout.buildDirectory.dir("ensemble/jniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildEnsemble, BuildEnsemble::jniLibs)
}
