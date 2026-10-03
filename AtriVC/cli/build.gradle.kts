// cli：在 PC(JVM) 上跑同一份 core 管线，用于与 Python 基准逐级对齐验证。
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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

application {
    mainClass.set("atri.cli.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
}

tasks.named<JavaExec>("run") {
    // 大模型 + 大堆
    jvmArgs("-Xmx4g")
}
