# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep model-related classes
-keep class com.dialect.interpreter.inference.** { *; }
-keep class com.dialect.interpreter.audio.** { *; }
-keep class com.dialect.interpreter.data.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin serialization
-keepclassmembers class kotlinx.serialization.** { *; }
