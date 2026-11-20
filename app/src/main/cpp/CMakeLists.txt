cmake_minimum_required(VERSION 3.22.1)

# whisper.cpp is unusably slow without optimization, so it is built as Release even in
# debug app builds. A normal variable overrides the Debug value Android Gradle passes in.
set(CMAKE_BUILD_TYPE Release)

project(catchpopped_whisper C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Static whisper/ggml inside one shared library; no tools, tests or examples.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

# Galaxy S21 (Cortex-X1/A78/A55) is ARMv8.2-A with dot product and FP16.
# ggml uses its own thread pool instead of OpenMP, which avoids packaging libomp.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(catchpopped_whisper SHARED whisper_jni.cpp)
target_link_libraries(catchpopped_whisper PRIVATE whisper log)
