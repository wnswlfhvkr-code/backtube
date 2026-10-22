import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import javax.inject.Inject

/*
 * Debug-only local synthetic fault diagnostic.
 *
 * It reuses exactly three PR7 sources from ../app/src/main/java through the explicit allowlist
 * below. No app project dependency, manifest, Application, ACRA, WorkManager or network code is
 * compiled into this module. The allowlisted files are staged under build/ at build time only;
 * no source copy is kept in the repository.
 */
plugins {
    alias(libs.plugins.android.application)
}

val sharedFaultSourceAllowlist = listOf(
    "org/schabi/newpipe/error/autoreport/SanitizedAppFault.java",
    "org/schabi/newpipe/error/autoreport/AppFaultOutbox.java",
    "org/schabi/newpipe/error/autoreport/AppFaultRecorder.java"
)
val sharedFaultSourceRoot = layout.projectDirectory.dir("../app/src/main/java")

abstract class SyncSharedFaultSources : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sharedSources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun sync() {
        val target = outputDirectory.get().dir("org/schabi/newpipe/error/autoreport").asFile
        fileSystem.sync {
            from(sharedSources)
            into(target)
        }
    }
}

val syncSharedFaultSources = tasks.register<SyncSharedFaultSources>("syncSharedFaultSources") {
    sharedSources.from(sharedFaultSourceAllowlist.map { sharedFaultSourceRoot.file(it) })
    outputDirectory.set(layout.buildDirectory.dir("generated/sharedFaultSources"))
}

configure<ApplicationExtension> {
    compileSdk {
        version = release(NEWPIPE_VERSION_SDK_COMPILE_MAJOR) {
            minorApiLevel = NEWPIPE_VERSION_SDK_COMPILE_MINOR
        }
    }
    namespace = "org.schabi.newpipe.faultdiagnostic"

    defaultConfig {
        applicationId = "org.schabi.newpipe.pr7faulttest20261008"
        minSdk {
            version = release(23)
        }
        targetSdk {
            version = release(35)
        }
        // Diagnostic APK version, intentionally separate from the payload product version.
        versionCode = 1
        versionName = "1.0-pr7-local"

        // Payload version written into the sanitized record (shared ProjectConfig value).
        buildConfigField("int", "PRODUCT_VERSION_CODE", NEWPIPE_VERSION_CODE.toString())
    }

    compileOptions {
        // java.util.Optional on API 23 requires core library desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        encoding = "utf-8"
    }

    buildFeatures {
        buildConfig = true
    }
}

configure<ApplicationAndroidComponentsExtension> {
    // Debug only: the release variant is never configured or built.
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.enable = false
    }
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(
            syncSharedFaultSources,
            SyncSharedFaultSources::outputDirectory
        )
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    testImplementation(libs.junit)
}
