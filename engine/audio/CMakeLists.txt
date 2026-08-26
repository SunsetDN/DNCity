cmake_minimum_required(VERSION 3.20)
project(dncity_audio LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

# codec2 (vendor/codec2, git submodule -- see vendor/README.md) needs a real C99 `_Complex` type
# (fsk.c/fmfsk.c/ofdm.c etc. use `complex float` arithmetic directly), which clang cannot provide
# when targeting the MSVC ABI -- MSVC's own CRT has never supported `_Complex`, and clang's
# MSVC-target `<complex.h>` is consequently a no-op. This is why this whole module (not just
# codec2) is built for the `x86_64-w64-mingw32` target instead: mingw's libc genuinely supports
# C99 complex, so codec2 compiles unmodified, and everything else here (miniaudio, the JNI glue)
# is portable C/C++ that doesn't care which ABI it's built for. See build.gradle.kts for the
# toolchain flags (sysroot/gcc-lib paths, `-fuse-ld=lld`) that make this work with only clang.exe
# and LLVM's own ld.lld.exe -- no invocation of any MinGW-w64-provided binary (blocked by this
# dev machine's Application Control policy, see CLAUDE.md) is required, since clang/lld only ever
# *read* the MinGW sysroot's headers/import libraries, never execute anything from it.
if(WIN32)
    # codec2's CMakeLists.txt unconditionally links several targets (generate_codebook, several
    # demo executables) against "m" -- real on UNIX, but mingw's C runtime has no separate libm to
    # link (math functions live in the same CRT as everything else), so this is a no-op stand-in.
    add_library(m INTERFACE)
endif()
set(UNITTEST OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/codec2)

add_library(dncity_audio SHARED
    src/main/cpp/miniaudio_impl.c
    src/main/cpp/jni_audio.cpp
    src/main/cpp/jni_codec2.cpp
)

target_include_directories(dncity_audio PRIVATE
    vendor
    ${JNI_INCLUDE_DIRS}
    # codec2's own CMakeLists.txt advertises its generated codec2/version.h via
    # $<BUILD_INTERFACE:${CMAKE_BINARY_DIR}>, which is the *top-level* binary dir -- fine when
    # codec2 is the top-level project (its own CMAKE_BINARY_DIR == PROJECT_BINARY_DIR then), but
    # wrong here where it's add_subdirectory'd: the file actually lands one level deeper, under
    # this subdirectory's own binary dir. Point directly at where it's actually generated instead
    # of relying on that (in this one case, incorrect) INTERFACE include dir.
    ${CMAKE_CURRENT_BINARY_DIR}/vendor/codec2
)

target_link_libraries(dncity_audio PRIVATE codec2)

if(WIN32)
    # CMake's mingw-target shared-library naming defaults to the GNU convention (libdncity_audio.dll),
    # same as it would on Linux -- but build.gradle.kts (and NativeLibrary.java's classpath resource
    # lookup) expect the unprefixed Windows-native "dncity_audio.dll" name this project has always
    # used, so drop the prefix explicitly.
    set_target_properties(dncity_audio PROPERTIES PREFIX "")
endif()

if(WIN32)
    # miniaudio's WASAPI/DirectSound/WinMM backends pull these in. Not linking ${JNI_LIBRARIES}
    # (jvm.lib) here -- JNIEXPORT functions are only ever called *by* the JVM via function
    # pointers it already passes in (JNIEnv*), never called *into* the JVM by symbol, so nothing
    # in this library actually needs it, and jvm.lib is an MSVC-format import library lld's
    # mingw-target linking can't consume anyway.
    target_link_libraries(dncity_audio PRIVATE ole32 winmm)
endif()

if(UNIX AND NOT APPLE)
    find_package(Threads REQUIRED)
    target_link_libraries(dncity_audio PRIVATE Threads::Threads dl m)
endif()

if(APPLE)
    target_link_libraries(dncity_audio PRIVATE "-framework CoreAudio" "-framework CoreFoundation" "-framework AudioToolbox")
endif()
