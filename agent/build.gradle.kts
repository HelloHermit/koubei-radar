import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: LLM clients (Claude via the official SDK, OpenAI-compatible via OkHttp),
// the tool-calling shop advisor agent, and the screenshot/text import extractor.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    api(project(":data"))
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.anthropic.java)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    useJUnit()
}
