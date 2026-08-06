# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep model-related classes reachable via reflection/JNI
-keep class com.dialect.interpreter.inference.NativeHyMtRuntime { *; }

# Kotlin serialization
-keepclassmembers class kotlinx.serialization.** { *; }
