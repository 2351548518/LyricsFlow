plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.lyricsflow"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.lyricsflow"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = providers.environmentVariable("LYRICSFLOW_KEYSTORE_FILE").orNull?.let(::file)
            storePassword = providers.environmentVariable("LYRICSFLOW_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("LYRICSFLOW_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("LYRICSFLOW_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

abstract class BundleStyleSchema : DefaultTask() {
    @get:InputFile abstract val schemaFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val output = outputDirectory.file("lyricsflow.style.schema.json").get().asFile
        output.parentFile.mkdirs()
        schemaFile.get().asFile.copyTo(output, overwrite = true)
    }
}

val bundleStyleSchema =
    tasks.register<BundleStyleSchema>("bundleStyleSchema") {
        schemaFile.set(
            rootProject.layout.projectDirectory.file("docs/lyricsflow.style.schema.json")
        )
        outputDirectory.set(layout.buildDirectory.dir("generated/styleSchemaAssets"))
    }

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        bundleStyleSchema,
        BundleStyleSchema::outputDirectory,
    )
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
