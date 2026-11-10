import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins { id("com.android.library") }

android {
    namespace = "work.stdpi.ensemble"
    compileSdk = 37
    ndkVersion = "25.2.9519653"
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
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
        include("Cargo.toml", "build.rs", "src/**")
    })
    rustInputs.from(rootProject.layout.projectDirectory.file("Cargo.toml"))
    rustInputs.from(rootProject.layout.projectDirectory.file("Cargo.lock"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    cargoTarget.set(layout.buildDirectory.dir("cargo"))
    jniLibs.set(layout.buildDirectory.dir("ensemble/jniLibs"))
}

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildEnsemble, BuildEnsemble::jniLibs)
}
