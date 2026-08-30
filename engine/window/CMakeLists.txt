cmake_minimum_required(VERSION 3.20)
project(dncity_window LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(JNI REQUIRED)

add_library(dncity_window SHARED
    src/main/cpp/jni_window.cpp
)

target_include_directories(dncity_window PRIVATE ${JNI_INCLUDE_DIRS})

# No C99 `_Complex`-style constraint here (unlike engine/audio, see its CMakeLists.txt), so this
# builds with the default MSVC toolchain on Windows -- plain JNI glue over Win32's windowing API
# (user32.lib, which ships with every MSVC install; no vendored import libraries needed, unlike
# engine/fmod). MSVC's default shared-library naming already omits the "lib" prefix mingw-target
# builds add, so no PREFIX override is needed either.
if(WIN32)
    target_link_libraries(dncity_window PRIVATE user32)
endif()
