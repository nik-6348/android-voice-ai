# Proguard / R8 rules for Nik's voice (release builds)

# App classes are kept whole: Gson reads and writes them by field name (history, automations, tool
# payloads), and the accessibility service / receivers are named in the manifest
-keep class com.nik.voiceassistant.** { *; }

# Gson
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
# R8 full mode drops generic signatures that Gson reads (List<TranscriptItem> fields, TypeTokens)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Vosk (wake word) calls its native library through JNA, by class and method name
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# Release builds drop routine logs (they can include what the user said or what was on screen);
# warnings and errors stay
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Tink (EncryptedSharedPreferences) references compile-time-only annotations
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
