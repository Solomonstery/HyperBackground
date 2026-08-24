plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("HYPERBG_KEYSTORE_PATH").orNull
    ?: error("Missing required private signing environment variable: HYPERBG_KEYSTORE_PATH")
val releaseKeystorePassword = providers.environmentVariable("HYPERBG_KEYSTORE_PASSWORD").orNull
    ?: error("Missing required private signing environment variable: HYPERBG_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("HYPERBG_KEY_ALIAS").orNull
    ?: error("Missing required private signing environment variable: HYPERBG_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("HYPERBG_KEY_PASSWORD").orNull
    ?: error("Missing required private signing environment variable: HYPERBG_KEY_PASSWORD")

android {
    namespace = "com.ciallo.hyperbackground"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ciallo.hyperbackground"
        minSdk = 33
        targetSdk = 35
        versionCode = 20
        versionName = "1.3.6"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.17.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")

    compileOnly(files("libs/xposed-stubs.jar"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
