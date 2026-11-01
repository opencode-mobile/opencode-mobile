# Crashlytics / Play stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep public class * extends java.lang.Exception

# Hilt, Room, Kotlin Serialization, Compose, Ktor, Navigation, and Firebase
# ship consumer ProGuard rules. Keep this file for app-owned crash attributes
# and any future project-specific keeps.

# Release builds carry no debug/verbose logcat lines (#755). DiagnosticLog already
# gates them on BuildConfig.DEBUG; this removes the remaining direct Log.d/Log.v calls.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
