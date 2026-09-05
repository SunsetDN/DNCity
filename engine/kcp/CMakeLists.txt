cmake_minimum_required(VERSION 3.20)
project(dncity_kcp LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(dncity_kcp SHARED
    src/main/cpp/jni_kcp.cpp
)

target_include_directories(dncity_kcp PRIVATE ${JNI_INCLUDE_DIRS})

# Plain portable C++17 (DncityKcp.h has no OS-specific calls at all -- see its own file comment),
# so this builds with the default MSVC toolchain on Windows, same as engine/fmod/engine/window --
# no C99 `_Complex`-style constraint here (unlike engine/audio's codec2 work), no vendored import
# libraries needed. MSVC's default shared-library naming already omits the "lib" prefix
# mingw-target builds add, so no PREFIX override is needed either.
