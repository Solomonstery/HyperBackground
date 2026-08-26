plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystorePath = providers.environmentVariable("HYPERBG_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("HYPERBG_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("HYPERBG_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("HYPERBG_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

if (releaseRequested && !releaseSigningReady) {
    error("Release builds require the private HYPERBG signing environment variables")
}

android {
    namespace = "com.ciallo.hyperbackground"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ciallo.hyperbackground"
        minSdk = 33
        targetSdk = 35
        versionCode = 34
        versionName = "1.4.0"
        resourceConfigurations += listOf("en", "zh-rCN")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        create("releaseFast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

// 构建前把仓库根目录的 CHANGELOG.md 同步进 assets，作为“本次版本说明”卡片的唯一数据源。
// 直接写入默认 assets 源集目录，避免向 Android SourceSet 传入 Provider。
val syncChangelogAsset by tasks.registering(Copy::class) {
    from(rootProject.file("CHANGELOG.md"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") {
    dependsOn(syncChangelogAsset)
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

    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
