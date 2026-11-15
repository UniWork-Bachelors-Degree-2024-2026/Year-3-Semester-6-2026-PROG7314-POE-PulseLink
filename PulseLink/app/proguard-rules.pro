# PulseLink ProGuard / R8 Obfuscation & Security Rules

# Retain Retrofit2 interfaces and reflection metadata
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Retain Gson serialization / deserialization models
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.prog7314_poepart23.data.remote.models.** { *; }
-keep class com.example.prog7314_poepart23.data.local.models.** { *; }
-keep class com.example.prog7314_poepart23.data.models.** { *; }

# Retain Firebase Auth, Messaging, Realtime DB
-keepattributes *Annotation*, SourceFile, LineNumberTable
-keepclassmembers class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Retain AndroidX Security & Crypto
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Retain Facebook Shimmer
-keep class com.facebook.shimmer.** { *; }

# Strip verbose debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}
