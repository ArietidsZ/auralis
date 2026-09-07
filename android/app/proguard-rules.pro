# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Keep model-related classes reachable via reflection/JNI
-keep class com.dialect.interpreter.inference.NativeHyMtRuntime { *; }

# sherpa-onnx JNI wrappers (ASR). Native methods must keep their names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { native <methods>; }

# Kotlin serialization
-keepclassmembers class kotlinx.serialization.** { *; }
