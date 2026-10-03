// core：纯 Kotlin 的 DSP + RVC 管线，Android 与 PC(JVM) 共用。
// ONNX Runtime 用 compileOnly —— 具体实现由宿主模块提供：
//   app -> onnxruntime-android (AAR)   cli -> onnxruntime (desktop jar)
// 两边暴露的 ai.onnxruntime.* Java API 是一致的。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}
