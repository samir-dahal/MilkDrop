cmake_minimum_required(VERSION 3.22.1)
project(milkdropjni)

# Build libprojectM (and projectm-playlist, same repo) from source as a
# dynamically-linked shared library, per the project requirements — this
# keeps LGPL-2.1 compliance simple and keeps presets/API in sync with the
# vendored submodule tag rather than a prebuilt CI artifact.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(ENABLE_PLAYLIST ON CACHE BOOL "" FORCE)
set(ENABLE_SDL_UI OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_EMSCRIPTEN OFF CACHE BOOL "" FORCE)

set(PROJECTM_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/projectm)

add_subdirectory(${PROJECTM_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/projectm_build)

# projectM's build appends a "d" debug postfix and gives the playlist library an
# "-4-playlist" suffixed name; pin both to fixed names so ProjectMBridge's
# System.loadLibrary calls don't need to vary by build type.
set_target_properties(projectM PROPERTIES DEBUG_POSTFIX "")
set_target_properties(projectM_playlist PROPERTIES DEBUG_POSTFIX "" OUTPUT_NAME "projectM_playlist")

add_library(milkdropjni SHARED milkdrop_jni.cpp)
set_target_properties(milkdropjni PROPERTIES DEBUG_POSTFIX "")

find_library(log-lib log)

target_include_directories(milkdropjni PRIVATE
    ${PROJECTM_SOURCE_DIR}/src/api/include
    ${PROJECTM_SOURCE_DIR}/src/playlist/api
)

target_link_libraries(milkdropjni
    PRIVATE
    projectM
    projectM_playlist
    ${log-lib}
)
