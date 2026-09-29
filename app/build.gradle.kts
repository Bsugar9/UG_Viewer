import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionCodeValue: Int = 200
val versionNameValue: String = "2.00"

// Release signing secrets are never committed. They are read from
// keystore.properties (git-ignored) and fall back to environment variables, so a
// release build works locally without the passwords living in version control.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingSecret(property: String, environmentVariable: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environmentVariable))
        ?.takeIf { it.isNotBlank() }

android {
    namespace = "com.ugviewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ugviewer"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeValue
        versionName = versionNameValue
    }

    signingConfigs {
        create("release") {
            val storePassword = signingSecret("storePassword", "UGVIEWER_STORE_PASSWORD")
            val keyPassword = signingSecret("keyPassword", "UGVIEWER_KEY_PASSWORD")
            if (storePassword != null && keyPassword != null) {
                storeFile = file("release-keystore.jks")
                this.storePassword = storePassword
                this.keyPassword = keyPassword
                keyAlias = signingSecret("keyAlias", "UGVIEWER_KEY_ALIAS") ?: "ugviewer"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Only sign when the secrets are available; otherwise fall back to the
            // debug key so `assembleRelease` still produces an installable APK.
            signingConfig = signingConfigs.getByName("release").takeIf {
                signingSecret("storePassword", "UGVIEWER_STORE_PASSWORD") != null
            } ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.compose.material3.adaptive:adaptive")
    implementation("androidx.compose.material3.adaptive:adaptive-layout")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
