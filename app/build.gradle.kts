import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
}

val releaseSigning = listOf("RELEASE_KEYSTORE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
require(releaseSigning.values.all { it.isNullOrBlank() } || releaseSigning.values.all { !it.isNullOrBlank() }) {
    "Release signing requires all four RELEASE_* environment variables."
}
val privateFontFiles = listOf("peyda_bold.ttf", "peyda_medium.ttf", "peyda_semibold.ttf", "yekanbakh_regular.ttf", "yekanbakh_bold.ttf")
    .map { layout.projectDirectory.file("src/main/res/font/$it").asFile }
require(privateFontFiles.none { it.exists() } || privateFontFiles.all { it.exists() }) {
    "Supply all five private fonts, or remove them to use the open-license fallback."
}

android {
    namespace = "com.doxigo.tv30"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.doxigo.tv30"
        // Sony's 2017 Bravias shipped on 7.0; 23 still covers every Android TV since 2015.
        minSdk = 23
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.0"
        testInstrumentationRunner = "com.doxigo.tv30.PerformanceChecks"
        // The TMDB v4 read token behind the trending row, from the gitignored local.properties
        // (`tmdb.token`). Without it the row is simply off.
        val tmdbToken = Properties().apply {
            providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
        }.getProperty("tmdb.token").orEmpty().trim()
        buildConfigField("String", "TMDB_TOKEN", "\"$tmdbToken\"")
    }

    if (releaseSigning["RELEASE_KEYSTORE"] != null) {
        signingConfigs.create("githubRelease") {
            storeFile = rootProject.file(releaseSigning.getValue("RELEASE_KEYSTORE")!!)
            storePassword = releaseSigning.getValue("RELEASE_STORE_PASSWORD")
            keyAlias = releaseSigning.getValue("RELEASE_KEY_ALIAS")
            keyPassword = releaseSigning.getValue("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("githubRelease") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    if (privateFontFiles.none { it.exists() }) {
        sourceSets.getByName("main").res.srcDir("src/openFonts/res")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.leanback:leanback:1.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-ui-leanback:$media3")
    // FrameExtractor: the episode tiles' frames, from the episodes' own video (EpisodeFrames.kt).
    implementation("androidx.media3:media3-inspector-frame:$media3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
