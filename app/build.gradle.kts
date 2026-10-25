import java.time.Duration

// AGP 9 ships built-in Kotlin: no kotlin-android plugin. jvmTarget follows compileOptions.targetCompatibility.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.koubeiradar.app"
    // AndroidX (Compose 1.12, core 1.19, lifecycle 2.11, navigation 2.10) and OkHttp 5.5 require compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.koubeiradar.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 shrinks, optimizes and obfuscates the release build; keep rules live in proguard-rules.pro (see docs/DESIGN.md,
            // "Release build / R8"). They have been checked against the R8 output only, not on a device or emulator.
            // The release APK stays unsigned: there is no keystore in the repository, so sign it yourself before installing.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/versions/**",
                "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric (smoke tests under src/test/.../smoke) needs the merged manifest, resources and assets on the classpath.
            isIncludeAndroidResources = true
            all {
                // Hard stop for a runaway Robolectric run (each smoke test also bounds itself, see SmokeTestBase).
                it.timeout.set(Duration.ofMinutes(15))
                it.maxHeapSize = "2g"
                // Robolectric native graphics + Compose rendering is memory hungry; one test JVM at a time keeps it predictable.
                it.maxParallelForks = 1
                // Robolectric downloads its android-all jar from Maven Central on first use. On a restricted or rate-limited
                // network pass -Probolectric.dependency.repo.url=<Maven Central mirror> (or set it in ~/.gradle/gradle.properties).
                providers.gradleProperty("robolectric.dependency.repo.url").orNull?.let { url ->
                    it.systemProperty("robolectric.dependency.repo.url", url)
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":agent"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // Material 3 does not expose the core icon set; version is managed by the Compose BOM (1.7.8).
    implementation("androidx.compose.material:material-icons-core")
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)

    // Runtime smoke tests: the real activity on the JVM (Robolectric) driven by Compose UI tests.
    // Robolectric 4.17 is the latest stable release at the time of writing; the androidx.test artifacts are the latest stable ones.
    testImplementation(platform(libs.compose.bom))
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.test.ext:junit:1.3.0")
    // Already on the runtime classpath through compose ui-test; declared for IdlingPolicies, which bounds how long a test waits for the UI.
    testImplementation("androidx.test.espresso:espresso-core:3.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Declares androidx.activity.ComponentActivity in the debug manifest, which the Compose test rules launch.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
