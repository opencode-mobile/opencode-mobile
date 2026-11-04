cmake_minimum_required(VERSION 3.22)
project(cpuaffinity C)

# One small JNI library: pinning the threads that run speech models to the phone's fast cores.
add_library(cpuaffinity SHARED cpu_affinity_jni.c)
target_link_options(cpuaffinity PRIVATE "-Wl,--strip-debug")
