plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.atri.vc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.atri.vc"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"
        ndk {
            // 目前只做离线文件转换，纯 Kotlin + ONNX Runtime（自带 x86_64/arm64 原生库）
            abiFilters += listOf("arm64-v8a", "x86_64")
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
        // 调试通路（AUTORUN 自动跑）依赖 BuildConfig.DEBUG
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
        jniLibs {
            // 全量包体积已经很大，原生库保持压缩（解包到 lib 目录，不影响加载）
            useLegacyPackaging = true
        }
    }

    // 全量包：把 model_assets/{models,tts} 一起打进 APK，用户装完首次启动自动导入。
    // -PbundleModels=true   → 全量包（约 950 MB，懒人一键）
    // 不加参数              → 轻量包（约 63 MB，开发调试用，模型自己 adb push）
    val bundleModels = (project.findProperty("bundleModels") as String?)?.toBoolean() ?: false
    androidResources {
        // 模型是已压缩过的二进制，再压一遍纯属浪费时间，还会让 openFd() 拿不到长度
        noCompress += listOf("onnx", "bin", "data", "refbin")
    }
    if (bundleModels) {
        sourceSets["main"].assets.srcDir("${rootProject.projectDir}/model_assets")
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // 录音 / 合成两页要用到 Mic、Stop、GraphicEq 这些不在 icons-core 里的图标
    implementation("androidx.compose.material:material-icons-extended")

    // ONNX Runtime Android（含 arm64-v8a / x86_64 原生库）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
