Place the Hy-MT native runtime here before building a production APK.

Expected Android ABI layout:

```text
jniLibs/
  arm64-v8a/
    libhymt_jni.so
```

`NativeHyMtRuntime` loads `hymt_jni` and expects the library to expose the JNI
entry points declared in `NativeHyMtRuntime.kt`. The Kotlin layer fails fast if
the library is missing so the app does not silently ship passthrough translation.
