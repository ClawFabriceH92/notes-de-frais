import java.io.File
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * The signing key has to be the SAME on every build, otherwise an install over
 * an existing app fails and the in-app updater is dead. Passwords come from the
 * environment in CI, and from the local secret files during development.
 */
fun secretFromFiles(key: String): String? {
    val candidates = listOf(
        File("/root/.secrets/keystores-android/notesdefrais-keystore.env"),
        File("/root/.secrets/keystores-android/passwords.env"),
    )
    for (file in candidates) {
        if (!file.exists()) continue
        val props = Properties()
        file.inputStream().use { props.load(it) }
        val value = props.getProperty(key) ?: props.getProperty(key.replaceFirst("NOTESDEFRAIS_", ""))
        if (!value.isNullOrBlank()) return value
    }
    return null
}

val storePassword: String? = System.getenv("NOTESDEFRAIS_KEYSTORE_PASSWORD")
    ?: secretFromFiles("NOTESDEFRAIS_KEYSTORE_PASSWORD")
val keyAliasValue: String = System.getenv("NOTESDEFRAIS_KEY_ALIAS") ?: "notesdefrais"
val keyPasswordValue: String? = System.getenv("NOTESDEFRAIS_KEY_PASSWORD") ?: storePassword

// CI ships the keystore as base64 in a secret; locally it lives on disk.
val keystoreFile: File? = run {
    val local = File("/root/.secrets/keystores-android/notesdefrais-release.keystore")
    val b64 = System.getenv("NOTESDEFRAIS_KEYSTORE_B64")
    when {
        !b64.isNullOrBlank() -> {
            val target = File(System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir"), "notesdefrais-release.keystore")
            target.writeBytes(Base64.getDecoder().decode(b64))
            target
        }
        local.exists() -> local
        else -> null
    }
}

android {
    namespace = "com.trucdecomptable.notesdefrais"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trucdecomptable.notesdefrais"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.6.2"
        resourceConfigurations += listOf("fr")
    }

    signingConfigs {
        if (keystoreFile != null && storePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = this@Build_gradle.storePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Local storage
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // On-device OCR, no network, no data leaving the phone
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // APK download for the in-app updater
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // org.json is a stub in unit tests otherwise
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
