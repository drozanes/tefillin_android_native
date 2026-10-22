# Proguard rules for Tefillin Native App

# MediaPipe
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# App Data Models & CV
-keep class com.drozanes.tefillin.model.** { *; }
-keep class com.drozanes.tefillin.cv.** { *; }

# ViewBinding & UI
-keep class com.drozanes.tefillin.databinding.** { *; }
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# JNI & Native Methods
-keepclasseswithmembernames class * {
    native <methods>;
}
