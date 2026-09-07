cmake_minimum_required(VERSION 3.22)
project(hymt_jni CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Fixed llama.cpp checkout — see README.md. The wrapper links llama.cpp's own
# CMake targets (llama), so include dirs, PIC and static deps are managed by
# upstream CMake instead of guessing archive paths.
if(NOT DEFINED LLAMA_CPP_DIR)
    message(FATAL_ERROR "LLAMA_CPP_DIR must point to the pinned llama.cpp checkout")
endif()

# Verify the checkout is exactly the pinned revision before anything else.
set(HYMT_PINNED_LLAMA_COMMIT 1e411d8f5a1e23525fa3265dfb4bd76265465397)
find_package(Git REQUIRED)
    execute_process(
        COMMAND ${GIT_EXECUTABLE} -C "${LLAMA_CPP_DIR}" rev-parse HEAD
        OUTPUT_VARIABLE LLAMA_ACTUAL_COMMIT
        OUTPUT_STRIP_TRAILING_WHITESPACE
        RESULT_VARIABLE GIT_RESULT
        ERROR_QUIET
    )
    if(NOT GIT_RESULT EQUAL 0)
        message(FATAL_ERROR "LLAMA_CPP_DIR is not a git checkout: ${LLAMA_CPP_DIR}")
    endif()
    if(NOT LLAMA_ACTUAL_COMMIT STREQUAL HYMT_PINNED_LLAMA_COMMIT)
        message(FATAL_ERROR
            "llama.cpp checkout ${LLAMA_ACTUAL_COMMIT} != pinned ${HYMT_PINNED_LLAMA_COMMIT}. "
            "git fetch origin pull/22836/head:pr-22836-stq_0 && "
            "git checkout ${HYMT_PINNED_LLAMA_COMMIT}")
    endif()

# Static llama built inside our build tree (no shared GGML shipping questions).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# The pinned upstream creates llama-common directly when BUILD_COMMON is ON.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
# Model downloads belong to the app; this wrapper needs no TLS dependency.
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
option(HYMT_HOST_BUILD "Build host shared core + smoke test" OFF)
if(HYMT_HOST_BUILD AND (ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "iOS"))
    message(FATAL_ERROR "HYMT_HOST_BUILD is only for a native desktop build")
endif()
if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS" AND NOT HYMT_HOST_BUILD)
    message(FATAL_ERROR "Use Android/iOS toolchain or explicitly set HYMT_HOST_BUILD=ON")
endif()
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
if(HYMT_HOST_BUILD)
    set(LLAMA_BUILD_TOOLS ON CACHE BOOL "" FORCE)
endif()
add_subdirectory(${LLAMA_CPP_DIR} llama.cpp-build EXCLUDE_FROM_ALL)

# This pinned revision counts graph bytes by advancing an initially null
# pointer. C pointer arithmetic there is undefined even without dereference.
# Use unsigned address arithmetic in a build-local copy; do not mutate the
# caller's checkout or suppress UBSan. All real allocation offsets are equal.
set(HYMT_GGML_SOURCE "${LLAMA_CPP_DIR}/ggml/src/ggml.c")
file(SHA256 "${HYMT_GGML_SOURCE}" HYMT_GGML_SOURCE_SHA256)
if(NOT HYMT_GGML_SOURCE_SHA256 STREQUAL "ac390bb9d652782ad511372920e1153b51164ff54f84110d629daca2ed1dd76b")
    message(FATAL_ERROR "Pinned ggml.c differs from the audited graph-size fix input")
endif()
file(READ "${HYMT_GGML_SOURCE}" HYMT_GGML_TEXT)
string(REPLACE "*p = (void *) ((char *) ptr + size);"
               "*p = (void *) ((uintptr_t) ptr + size);"
               HYMT_GGML_PATCHED_TEXT "${HYMT_GGML_TEXT}")
set(HYMT_GGML_PATCHED_SOURCE "${CMAKE_CURRENT_BINARY_DIR}/ggml-auralis.c")
file(WRITE "${HYMT_GGML_PATCHED_SOURCE}" "${HYMT_GGML_PATCHED_TEXT}")
get_target_property(HYMT_GGML_BASE_SOURCES ggml-base SOURCES)
list(FIND HYMT_GGML_BASE_SOURCES "ggml.c" HYMT_GGML_SOURCE_INDEX)
if(HYMT_GGML_SOURCE_INDEX LESS 0)
    message(FATAL_ERROR "Pinned ggml-base target no longer contains ggml.c")
endif()
list(REMOVE_AT HYMT_GGML_BASE_SOURCES ${HYMT_GGML_SOURCE_INDEX})
list(INSERT HYMT_GGML_BASE_SOURCES ${HYMT_GGML_SOURCE_INDEX} "${HYMT_GGML_PATCHED_SOURCE}")
set_property(TARGET ggml-base PROPERTY SOURCES "${HYMT_GGML_BASE_SOURCES}")

if(ANDROID)
    find_library(android_log_lib log REQUIRED)
endif()

# Shared inference core (C ABI): linked into the JNI .so on Android and built
# as a dylib on host for the Swift bridge smoke. Platform-free sources only.
add_library(hymt_core STATIC hymt_core.cpp)
target_include_directories(hymt_core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}" "${LLAMA_CPP_DIR}/include" "${LLAMA_CPP_DIR}/common")
target_link_libraries(hymt_core PUBLIC llama llama-common)
target_compile_options(hymt_core PRIVATE -O2 -fexceptions -frtti)

# NDK r27 defaults to 4KB ELF alignment. 16KB devices (Android 15+) require
# LOAD Align 0x4000. ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES must also be passed
# at cmake configure time so PAGE_SIZE is not baked to 4096.
# https://developer.android.com/guide/practices/page-sizes
if(ANDROID)
    add_library(hymt_jni SHARED hymt_jni.cpp)
    target_link_libraries(hymt_jni PRIVATE hymt_core ${android_log_lib})
    target_compile_options(hymt_jni PRIVATE -O2 -fexceptions -frtti)
    target_link_options(hymt_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
endif()

# Host-only targets (HYMT_HOST_BUILD=ON): shared hymt_core dylib + smoke test.
if(HYMT_HOST_BUILD)
    add_library(hymt_core_dylib SHARED hymt_core.cpp)
    target_include_directories(hymt_core_dylib PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${LLAMA_CPP_DIR}/include" "${LLAMA_CPP_DIR}/common")
    target_link_libraries(hymt_core_dylib PRIVATE llama llama-common)
    set_target_properties(hymt_core_dylib PROPERTIES OUTPUT_NAME hymt_core)
    add_executable(hymt_smoke tests/hymt_smoke.c)
    target_link_libraries(hymt_smoke PRIVATE hymt_core_dylib)
    target_include_directories(hymt_smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
    add_executable(hymt_concurrency tests/hymt_concurrency.c)
    target_link_libraries(hymt_concurrency PRIVATE hymt_core_dylib pthread)
    target_include_directories(hymt_concurrency PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
endif()
