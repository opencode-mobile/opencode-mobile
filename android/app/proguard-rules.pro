# Crashlytics / Play stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep public class * extends java.lang.Exception

# Hilt, Room, Kotlin Serialization, Compose, Ktor, Navigation, and Firebase
# ship consumer ProGuard rules. Keep this file for app-owned crash attributes
# and any future project-specific keeps.
