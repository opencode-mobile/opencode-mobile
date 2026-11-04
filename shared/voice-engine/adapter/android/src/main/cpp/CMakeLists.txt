cmake_minimum_required(VERSION 3.22)
project(hexvoice C CXX)

# The engine is always built optimised: unoptimised ggml is 10-20x slower, which makes debug builds useless
# for judging dictation. Only this library's own JNI file follows the app's debug setting.
set(CMAKE_BUILD_TYPE Release)

# transcribe.cpp at the revision Hex desktop pins in its Cargo.toml. scripts/sync-hex.mjs copies that
# revision into hex-upstream.json; this reads it from there, so a Hex sync moves Android along with it.
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../hex-upstream.json" HEX_PIN)
string(JSON TRANSCRIBE_REPO GET "${HEX_PIN}" transcribeCpp repository)
string(JSON TRANSCRIBE_REV GET "${HEX_PIN}" transcribeCpp rev)

set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_INSTALL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_OPENMP OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_METAL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_VULKAN OFF CACHE BOOL "" FORCE)
# Every arm64 phone from about 2018 on has dot-product instructions; ggml uses them for Q8_0.
# Measured on a Pixel 9 and a Galaxy A15 (10 s of speech, Parakeet V2 Q8_0): adding fp16 changed nothing,
# i8mm was 50% slower on the Pixel (it switches off ggml's tinyBLAS kernels), and KleidiAI was no faster.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)

include(FetchContent)
FetchContent_Declare(transcribe URL "${TRANSCRIBE_REPO}/archive/${TRANSCRIBE_REV}.tar.gz")
FetchContent_MakeAvailable(transcribe)
# transcribe.cpp finds its public header through CMAKE_SOURCE_DIR, which is ours when it's a dependency
target_include_directories(transcribe PUBLIC "${transcribe_SOURCE_DIR}/include")

add_library(hexvoice SHARED hex_voice_jni.c)
target_link_libraries(hexvoice PRIVATE transcribe log)
# drop unused model families and debug info: the library is about a third of the size
target_link_options(hexvoice PRIVATE "-Wl,--gc-sections" "-Wl,--strip-debug")
