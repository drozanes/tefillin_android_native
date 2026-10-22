# Proguard / R8 rules for Tefillin Native App

# 1. Attributes & Metadata for JNI and Reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# 2. Entire App Package (preserve all internal classes, ViewModels, Views, Utilities, and JNI bridges)
-keep class com.drozanes.tefillin.** { *; }
-keepclassmembers class com.drozanes.tefillin.** { *; }

# 3. MediaPipe & Protocol Buffers (CRITICAL for tasks-vision native C++ JNI)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# Suppress compile-time annotation processor warnings in bundled dependencies
-dontwarn javax.annotation.processing.**
-dontwarn javax.lang.model.**
-dontwarn com.google.auto.value.**

# 4. ONNX Runtime & JNI
-keep class ai.onnxruntime.** { *; }
-keep class ai.onnxruntime.extensions.** { *; }
-dontwarn ai.onnxruntime.**

# 5. CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# 6. AndroidX Lifecycle & ViewModel
-keep class androidx.lifecycle.** { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>();
    public <init>(android.app.Application);
}

# 7. Kotlin Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# 8. Custom Views and ViewBinding
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 9. JNI & Native Methods
-keepclasseswithmembernames class * {
    native <methods>;
}
