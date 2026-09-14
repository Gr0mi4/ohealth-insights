import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// The one place the version is declared. The launcher icon is generated from this value, so the
// icon on the home screen always names the build that is actually installed.
val appVersionCode = 26
val appVersionName = "0.7.3"

val launcherIconOutputDir: Provider<Directory> =
    layout.buildDirectory.dir("generated/res/launcherIcon")

val generateLauncherIcon = tasks.register("generateLauncherIcon") {
    val outputDir = launcherIconOutputDir
    val version = appVersionName
    inputs.property("versionName", version)
    outputs.dir(outputDir)
    doLast {
        val drawableDir = outputDir.get().asFile.resolve("drawable")
        drawableDir.mkdirs()
        drawableDir.resolve("ic_launcher.xml").writeText(launcherIconXml(version))
    }
}

android {
    namespace = "dev.gr0mi4.ohealthinsights"
    compileSdk = 36

    sourceSets.getByName("main").res.srcDir(launcherIconOutputDir)

    defaultConfig {
        applicationId = "dev.gr0mi4.ohealthinsights"
        minSdk = 28
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField(
            "String",
            "DRIVE_OAUTH_CLIENT_ID",
            "\"${localProperties.getProperty("DRIVE_OAUTH_CLIENT_ID", "")}\"",
        )
    }

    signingConfigs {
        val keystorePath = localProperties.getProperty("RELEASE_STORE_FILE")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(keystorePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub that throws; unit tests need a real implementation.
    testImplementation("org.json:json:20250107")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}

tasks.named("preBuild") {
    dependsOn(generateLauncherIcon)
}

/**
 * Draws the version string into the launcher icon as seven-segment glyphs.
 *
 * The digits are plain vector paths on a 12x22 cell, so no font or bitmap is involved and the file
 * stays a single adaptive-icon-friendly drawable. Because it is generated from [appVersionName],
 * bumping the version cannot leave a stale number on the icon.
 */
fun launcherIconXml(version: String): String {
    val digitWidth = 12
    val dotWidth = 4
    val gap = 4
    val top = 70
    val bottom = 92

    fun segments(x: Int): Map<Char, String> {
        val r = x + digitWidth
        val innerLeft = x + 3
        val innerRight = x + digitWidth - 3
        return mapOf(
            'A' to "M$x,$top H$r V${top + 3} H$x Z",
            'F' to "M$x,${top + 3} H$innerLeft V${top + 11} H$x Z",
            'B' to "M$innerRight,${top + 3} H$r V${top + 11} H$innerRight Z",
            'G' to "M$x,${top + 10} H$r V${top + 14} H$x Z",
            'E' to "M$x,${top + 11} H$innerLeft V${bottom - 3} H$x Z",
            'C' to "M$innerRight,${top + 11} H$r V${bottom - 3} H$innerRight Z",
            'D' to "M$x,${bottom - 3} H$r V$bottom H$x Z",
        )
    }

    val digitSegments = mapOf(
        '0' to "ABCDEF",
        '1' to "BC",
        '2' to "ABGED",
        '3' to "ABGCD",
        '4' to "FGBC",
        '5' to "AFGCD",
        '6' to "AFGECD",
        '7' to "ABC",
        '8' to "ABCDEFG",
        '9' to "ABCDFG",
    )

    val glyphs = version.filter { it.isDigit() || it == '.' }
    val totalWidth = glyphs.sumOf { if (it == '.') dotWidth else digitWidth } +
        gap * (glyphs.length - 1).coerceAtLeast(0)

    val left = 17
    val arrowLeft = 78
    val drawArrow = left + totalWidth < arrowLeft

    val paths = StringBuilder()
    var cursor = left
    glyphs.forEach { glyph ->
        if (glyph == '.') {
            paths.appendLine("""    <path
        android:fillColor="#FFFFFF"
        android:pathData="M$cursor,${bottom - 4}H${cursor + dotWidth}V${bottom}H$cursor Z" />
""")
            cursor += dotWidth + gap
        } else {
            val cell = segments(cursor)
            val data = digitSegments.getValue(glyph).map { cell.getValue(it) }.joinToString("")
            paths.appendLine("""    <!-- $glyph -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="$data" />
""")
            cursor += digitWidth + gap
        }
    }

    val arrow = if (drawArrow) {
        """    <path
        android:fillColor="#D9FFF8"
        android:pathData="M86,72L94,80H89V91H83V80H78Z" />
"""
    } else {
        ""
    }

    return """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from the project version by the generateLauncherIcon task. Do not edit by hand. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:fillColor="#111827"
        android:pathData="M18,4H90A14,14 0,0 1,104 18V90A14,14 0,0 1,90 104H18A14,14 0,0 1,4 90V18A14,14 0,0 1,18 4Z" />

    <path
        android:fillColor="#162337"
        android:pathData="M18,10H90A8,8 0,0 1,98 18V63H10V18A8,8 0,0 1,18 10Z" />

    <path
        android:fillColor="#00000000"
        android:pathData="M15,43L28,43L34,27L43,58L50,43L61,43L68,34L76,52L83,43L94,43"
        android:strokeColor="#4DE1C1"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:strokeWidth="5" />

    <path
        android:fillColor="#0EA58A"
        android:pathData="M10,63H98V90A8,8 0,0 1,90 98H18A8,8 0,0 1,10 90Z" />

$paths$arrow</vector>
"""
}
