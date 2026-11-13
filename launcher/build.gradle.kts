import java.util.Properties

plugins {
    alias(libs.plugins.wemu.android.application)
    alias(libs.plugins.wemu.android.application.compose)
}

val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}

val filteredLauncherAssets = layout.buildDirectory.dir("generated/filteredLauncherAssets")
val prepareFilteredLauncherAssets by tasks.registering(Sync::class) {
    from(layout.projectDirectory.dir("src/main/assets"))
    into(filteredLauncherAssets)
}

android {
    namespace = "com.dualscreen.launcher"

    defaultConfig {
        applicationId = "com.dualscreen.launcher"
        versionCode = providers.gradleProperty("launcherVersionCode").orNull?.toIntOrNull() ?: 29
        versionName = providers.gradleProperty("launcherVersionName").orNull ?: "1.6.17"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    val localReleaseSigning = if (releaseSigningPropertiesFile.isFile) {
        signingConfigs.create("release") {
            storeFile = rootProject.file(checkNotNull(releaseSigningProperties.getProperty("storeFile")))
            storePassword = checkNotNull(releaseSigningProperties.getProperty("storePassword"))
            keyAlias = checkNotNull(releaseSigningProperties.getProperty("keyAlias"))
            keyPassword = checkNotNull(releaseSigningProperties.getProperty("keyPassword"))
            enableV1Signing = true
            enableV2Signing = true
        }
    } else {
        null
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Device-test builds must update the installed release-signed
            // launcher in place; using Android's generated debug key makes an
            // otherwise valid APK fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            signingConfig = localReleaseSigning
        }
        release {
            // Minify the release requested for device testing. Resource shrinking stays
            // disabled because the native runtime resolves several packaged resources by
            // name and because it adds a second high-memory analysis pass.
            isMinifyEnabled = true
            isShrinkResources = false
            signingConfig = localReleaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { buildConfig = true }
    androidResources { noCompress += listOf("tzst", "zst", "tgz", "so", "dat", "dll") }
    // AndroidSourceDirectorySet's exclude filter is not applied by AGP's asset
    // merger. Stage a filtered tree instead, otherwise stale native payloads
    // silently ship inside every APK. Isaac bundles its native mod and assets.
    sourceSets["main"].assets.setSrcDirs(listOf(filteredLauncherAssets.get().asFile))
    packaging { jniLibs { useLegacyPackaging = true } }
}

tasks.named("preBuild").configure {
    dependsOn(prepareFilteredLauncherAssets)
}

dependencies {
    implementation(projects.engine)
    implementation(projects.secondscreen)
    implementation(projects.core.common)
    implementation(projects.core.model)
    implementation(projects.core.designsystem)
    implementation(projects.core.display)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
}
