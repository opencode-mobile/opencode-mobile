# Benchmark build type only (#872 Highlighting Lab). The measured engines stay optimized as
# in release; the Lab's measuring code keeps its class names so failure notes and stack
# traces in reports stay readable. Grammar assets are never shrunk (isShrinkResources is off).
-keepnames class dev.opencode.mobile.highlight.bench.** { *; }
-keepnames class dev.opencode.mobile.lab.** { *; }

# Tink (security-crypto) references compile-time errorprone annotations. Release gets them
# through Firebase; benchmark is Firebase-free like debug, so tell R8 they are not needed.
-dontwarn com.google.errorprone.annotations.**
