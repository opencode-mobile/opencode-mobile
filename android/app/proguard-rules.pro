# Crashlytics / Play stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep public class * extends java.lang.Exception

# Joni/jcodings (Android TextMate regex). R8 must not rewrite QuantifierNode's
# nested ReduceType enum or REDUCE_TABLE static initializer; that NPEs in
# <clinit> and crashes highlighting (ExceptionInInitializerError).
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-dontwarn org.joni.**
-dontwarn org.jcodings.**

# Hilt, Room, Kotlin Serialization, Compose, Ktor, Navigation, and Firebase
# ship consumer ProGuard rules. Keep this file for app-owned crash attributes
# and any future project-specific keeps.

# Release builds carry no debug/verbose logcat lines (#755). DiagnosticLog already
# gates them on BuildConfig.DEBUG; this removes the remaining direct Log.d/Log.v calls.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
