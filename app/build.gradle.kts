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
        versionCode = 24
        versionName = "1.6.0"

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
        unitTests.isIncludeAndroidResources = true
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
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit5.vintage)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    // Compose UI 不变量测试：锁住「分页列表渲染时必须用 get 触发 access hint」
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.manifest)
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
//
// ⚠️ 验证范围**只认 debug 变体**，且**必须**跑 `koverVerifyDebug`（不是 `koverVerify`）。
//
// 背景：Kover 0.9.0 的 `reports { total { } }` 跨变体聚合，`koverVerify` 会把
// `testReleaseUnitTest` 一起拖进任务图；而 release 变体有 9 个**已知失败**
// （compose-ui-test-manifest 只进 debug 清单，根因见 docs/ARCHITECTURE.md §11.3），
// 于是 CI 的 `./gradlew :app:testDebugUnitTest :app:koverHtmlReport :app:koverVerify`
// 会被这 9 个打红——门禁被一个「已知且刻意不修」的变体差异绑架。
//
// 那 9 个的正解（给 release 清单补 ComponentActivity）**刻意不做**：那条声明是
// `android:exported="true"`，等于往 release APK 里塞一个谁都能拉起的空 activity。
// Compose UI 单测因此只在 debug 变体跑，覆盖率门禁跟随同一变体。
//
// 为什么不是 `currentProject { providedVariant("debug") }`：0.9.0 里 `providedVariant`
// 只是**改写某个变体的配置**（KMP 用），不裁剪变体集合——total 依然聚合全部变体，
// 实测 `koverVerify` 的任务图照旧含 `testReleaseUnitTest`。能真正裁剪的只有
// 「把规则挂到 `variant("debug")`」这一条，代价是门禁任务名随之变成 `koverVerifyDebug`。
kover {
    reports {
        variant("debug") {
            html {
                // 保持 CI 上传的产物路径不变（app/build/reports/kover/html）
                htmlDir.set(layout.buildDirectory.dir("reports/kover/html"))
            }
            verify {
                rule {
                    // line 覆盖率实测 24%（2026-10，debug 变体）；门槛留余量取 20%，
                    // 让回归能被 CI 卡住而不是形同虚设（原值 9% 比实际低 15 个点）。
                    // 提高时先跑 ./gradlew :app:koverVerifyDebug 确认。
                    // 目标：20%（当前）→ 50%（UI Kit + 工具类补齐后）
                    minBound(20)
                }
            }
        }
    }
}


// Detekt 静态分析配置
detekt {
    config.setFrom(rootProject.file("config/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = false
    ignoreFailures = false  // CI 阻断：本次重构后启用
}
