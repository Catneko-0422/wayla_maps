import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.secrets)
}

android {
    namespace = "cc.nekocat.waylamaps"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.nekocat.waylamaps"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    sourceSets {
        // Character packs whose licenses forbid redistribution (the repo is public): kept out of
        // git, bundled only in local builds.
        getByName("main").assets.directories.add("src/local/assets")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the Navigation SDK.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// MAPS_API_KEY comes from the gitignored local.properties; local.defaults.properties keeps
// builds working without one.
secrets {
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}

/**
 * Copies the Navigation SDK's NOTICE.txt and LICENSE out of its AAR into assets/legal/, so the
 * attribution the SDK requires always matches the SDK version in use.
 */
abstract class ExtractNavigationLegal : DefaultTask() {
    @get:InputFiles
    abstract val aars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun extract() {
        files.sync {
            aars.forEach { from(archives.zipTree(it)) }
            include("NOTICE.txt", "LICENSE")
            rename("NOTICE.txt", "navigation_notice.txt")
            rename("LICENSE", "navigation_licenses.txt")
            // The AAR stores these read-only, which would block the next sync.
            filePermissions { unix("rw-r--r--") }
            into(outputDir.dir("legal"))
        }
    }
}

val navigationAar: Configuration = configurations.create("navigationAar") {
    isTransitive = false
    isCanBeConsumed = false
}

val extractNavigationLegal = tasks.register<ExtractNavigationLegal>("extractNavigationLegal") {
    aars.from(navigationAar)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(extractNavigationLegal, ExtractNavigationLegal::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // The Navigation SDK bundles its own Maps SDK; the standalone one must not be on the classpath.
    implementation(libs.google.navigation)
    navigationAar(libs.google.navigation)
    implementation(libs.google.places) {
        exclude(group = "com.google.android.gms", module = "play-services-maps")
    }
    // Android Auto.
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    // 3D character view (glTF models).
    implementation(libs.filament.android)
    implementation(libs.filament.gltfio)
    implementation(libs.filament.utils)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
