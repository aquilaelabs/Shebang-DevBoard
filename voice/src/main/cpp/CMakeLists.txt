# The speech engine for Shebang Voice: whisper.cpp (vendored, CPU only) behind a small JNI bridge.
cmake_minimum_required(VERSION 3.22)
project(shebangvoice C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)

# One static ggml with its CPU backend, built for the target ABI rather than this machine; no OpenMP (ggml's
# own threads do), no dynamically loaded backends.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
# The emulator (x86_64) runs on desktop CPUs, all of which have AVX2; phones (arm64) get their instructions
# chosen below.
if (ANDROID_ABI STREQUAL "x86_64")
    set(GGML_AVX ON CACHE BOOL "" FORCE)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
    set(GGML_FMA ON CACHE BOOL "" FORCE)
    set(GGML_F16C ON CACHE BOOL "" FORCE)
endif()
add_subdirectory(${WHISPER_DIR}/ggml ggml)

add_library(whisper STATIC ${WHISPER_DIR}/src/whisper.cpp)
target_include_directories(whisper PUBLIC ${WHISPER_DIR}/include PRIVATE ${WHISPER_DIR}/src)
target_compile_definitions(whisper PRIVATE WHISPER_VERSION="1.9.4")
target_link_libraries(whisper PUBLIC ggml)

add_library(shebangvoice SHARED voice_jni.c)
target_link_libraries(shebangvoice PRIVATE whisper log)
target_link_options(shebangvoice PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
