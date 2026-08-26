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
