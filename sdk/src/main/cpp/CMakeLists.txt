cmake_minimum_required(VERSION 3.22.1)
project(speech_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# speech-core (git submodule)
#
# Pull in speech-core via add_subdirectory so we reuse its target definitions
# rather than re-listing sources here. SPEECH_CORE_WITH_ONNX=ON adds the
# speech_core_models target (Silero VAD / Parakeet STT / Kokoro TTS /
# DeepFilterNet wrappers). Tests and examples are off — the Android NDK
# can't run host ctest binaries anyway.
# ---------------------------------------------------------------------------

if(NOT DEFINED SPEECH_CORE_DIR)
    message(FATAL_ERROR "SPEECH_CORE_DIR must be set (path to the speech-core submodule)")
endif()

if(NOT DEFINED ORT_DIR)
    message(FATAL_ERROR "ORT_DIR must be set (path to onnxruntime with include/ and lib/${ANDROID_ABI}/libonnxruntime.so)")
endif()

set(SPEECH_CORE_WITH_ONNX     ON  CACHE BOOL "" FORCE)
set(SPEECH_CORE_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(SPEECH_CORE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

add_subdirectory(${SPEECH_CORE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/speech_core)

# ---------------------------------------------------------------------------
# speech-android native library — JNI bridge only
#
# All audio DSP / model wrappers / onnx_engine now live in speech-core's
# speech_core_models target. The bridge just constructs them and feeds
# them into speech_core::VoicePipeline.
# ---------------------------------------------------------------------------

add_library(speech_android SHARED jni_bridge.cpp)

target_link_libraries(speech_android
    PRIVATE
        speech_core_models
        android
        log
)
