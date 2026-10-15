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
        versionCode = 8
        versionName = "1.2.0"
        testInstrumentationRunner = "com.doxigo.tv30.PerformanceChecks"
        // TMDB (Tmdb.kt), the subtitle sites (Subtitles.kt) and the translating models (Translator.kt)
        // take each viewer's own key, entered in Settings. These are the developer's fallback for their
        // own builds, from the gitignored local.properties (`tmdb.token`, `subdl.key`, `opensubtitles.key`,
        // `gemini.key`, `anthropic.key`, `openai.key`); Actions passes none, so a public APK carries no
        // key and nobody spends anyone else's.
        val local = Properties().apply {
            providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
        }
        for ((field, property) in listOf(
            "TMDB_TOKEN" to "tmdb.token", "SUBDL_KEY" to "subdl.key", "OPENSUBTITLES_KEY" to "opensubtitles.key",
            "GEMINI_KEY" to "gemini.key", "ANTHROPIC_KEY" to "anthropic.key", "OPENAI_KEY" to "openai.key",
        )) {
            buildConfigField("String", field, "\"${local.getProperty(property).orEmpty().trim()}\"")
        }
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
    // The real org.json, in place of android.jar's stubs: Translator's live check talks JSON on the JVM.
    testImplementation("org.json:json:20250517")
}
