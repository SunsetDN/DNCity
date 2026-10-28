cmake_minimum_required(VERSION 3.20)
project(dncity_fmod LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(dncity_fmod SHARED
    src/main/cpp/jni_fmod.cpp
)

target_include_directories(dncity_fmod PRIVATE ${JNI_INCLUDE_DIRS})

# No C99 `_Complex`/codec2-style constraint here (unlike engine/audio, see its CMakeLists.txt),
# so this builds with the default MSVC toolchain on Windows -- plain JNI glue code linked
# directly against the vendored FMOD import libraries. MSVC's default shared-library naming
# already omits the "lib" prefix mingw-target builds add, so no PREFIX override is needed either.
if(WIN32)
    target_link_directories(dncity_fmod PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src/main/resources/windows/x86_64)
    target_link_libraries(dncity_fmod PRIVATE fmod_vc fmodstudio_vc)
endif()

# Linux x86-64: link the vendored libraries by full path -- the versioned files, since the
# unversioned libfmod.so/libfmod.so.14 here are committed symlink stubs (text files naming their
# target; see FModLoad.realLibraryResource). Their sonames (libfmod.so.14, libfmodstudio.so.14)
# become this library's DT_NEEDED entries, which the dynamic loader satisfies from the copies
# FModLoad has already System.load()ed by the time it loads this one (glibc matches an
# already-loaded object by soname), so no rpath is needed -- and CMake's default build-tree rpath
# (this checkout's absolute path) is switched off so it never ships. fmod_min.h only uses
# int/float/pointer types, so its declarations hold on LP64 as well as on Windows' LLP64.
if(UNIX AND NOT APPLE)
    set(FMOD_LINUX_DIR ${CMAKE_CURRENT_SOURCE_DIR}/src/main/resources/linux/x86_64)
    target_link_libraries(dncity_fmod PRIVATE ${FMOD_LINUX_DIR}/libfmod.so.14.7 ${FMOD_LINUX_DIR}/libfmodstudio.so.14.7)
    set_target_properties(dncity_fmod PROPERTIES SKIP_BUILD_RPATH TRUE)
endif()
