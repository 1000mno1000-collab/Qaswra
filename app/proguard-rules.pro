-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin & Coroutines / Serialization
-dontwarn kotlinx.serialization.**
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep @kotlinx.serialization.Serializable class com.example.** { *; }
-keepclassmembers class com.example.**$Companion {
    kotlinx.serialization.KSerializer serializer();
}

# Sherpa-ONNX JNI callbacks & reflection-invoked model configs
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# FFmpegKit JNI & native callbacks
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Firebase, Firestore models & Google Credential Manager
-keepclassmembers class com.example.data.remote.** { *; }
-keepclassmembers class com.example.model.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Room entities/DAOs & BuildConfig
-keep class com.example.data.local.** { *; }
-keep class com.example.BuildConfig { *; }
