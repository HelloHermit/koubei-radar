# Keep rules for the release build (R8). Not verified at runtime: only the R8 output was inspected, never an installed app.
#
# Most libraries ship their own consumer rules, which AGP applies automatically (checked in the Gradle cache):
#  - anthropic-java-core: META-INF/proguard/anthropic-java-core.pro (Jackson annotations, kotlin.Metadata, enums, TypeReference)
#  - kotlinx-serialization-core: META-INF/com.android.tools/r8/*.pro (serializer()/Companion/INSTANCE of @Serializable classes)
#  - OkHttp (okhttp.aar proguard.txt: -dontwarn for optional platforms), WorkManager (ListenableWorker constructors),
#    DataStore, kotlinx-coroutines-android
# Jackson, Okio and the jackson-module-kotlin jar ship none, so what follows covers what those rely on.

# Annotations, generic signatures and nesting information that Jackson and the Kotlin reflection it uses read at runtime.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Anthropic SDK: Jackson binds its request/response/stream classes by reflection. The SDK's own rules only keep annotated
# members, and we cannot test which other members matter, so keep the whole package unrenamed. The cost is APK size.
-keep class com.anthropic.** { *; }

# Jackson itself: annotations are read reflectively, and Kotlin reflection (jackson-module-kotlin) needs its metadata.
-keep class com.fasterxml.jackson.annotation.** { *; }
-keep class kotlin.Metadata { *; }

# Optional or unavailable classes that R8 reports as missing (taken from the generated missing_rules.txt):
#  - AutoValue: annotation processor annotation referenced by OpenTelemetry API classes inside the Anthropic SDK dependencies.
#  - AnnotatedType / AnnotatedParameterizedType: absent from the Android API; only the SDK's structured-outputs support
#    (JSON schema generation from classes) refers to them. This app never uses structured outputs, so that path is not reached.
-dontwarn com.google.auto.value.AutoValue
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
