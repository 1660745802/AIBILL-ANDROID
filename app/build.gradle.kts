import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
    alias(libs.plugins.detekt)
}

// 读取签名配置（keystore.properties 不提交到 git）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.aibill.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aibill.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    room {
        schemaDirectory("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.room.paging)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi)
    ksp(libs.moshi.kotlin.codegen)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Serialization (Navigation type-safe routes)
    implementation(libs.serialization.json)

    // WorkManager
    implementation(libs.work.runtime)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Security
    implementation(libs.security.crypto)
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // Logging
    implementation(libs.timber)

    // Paging
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // Glance (Widget)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Testing
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.room.testing)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    debugImplementation(libs.compose.ui.test.manifest)
}


// JUnit5 Platform 启用（PR 14：单元测试）
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Kover 覆盖率配置（PR：CI 化的第一步）
kover {
    reports {
        total {
            verify {
                rule {
                    // 当前覆盖率 ~9.8%（存量代码），随测试补齐逐步提高
                    // 目标：先 20%（关键 VM + Repository）→ 50%（UI Kit + 工具类）
                    minBound(9)
                }
            }
        }
    }
}

// 编译时同步 scripts/rules.json → assets/default_rules.json（保持单一数据源）
tasks.register<Copy>("syncDefaultRules") {
    from(rootProject.file("scripts/rules.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
    rename { "default_rules.json" }
}
tasks.configureEach {
    if (name.contains("Assets") || name.contains("Lint") || name.contains("lint")) {
        dependsOn("syncDefaultRules")
    }
}

// Detekt 静态分析配置
detekt {
    config.setFrom(rootProject.file("config/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
    ignoreFailures = false  // CI 阻断：本次重构后启用
}
