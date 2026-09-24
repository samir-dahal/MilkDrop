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

# projectM's generated version.h reads CMAKE_PROJECT_VERSION_*, which only the *top-level*
# project() sets — i.e. this wrapper, not projectM — so a fresh configure left them empty and
# ProjectMCWrapper.cpp failed to compile. Mirror the submodule's own declared version instead.
file(READ ${PROJECTM_SOURCE_DIR}/CMakeLists.txt PROJECTM_CMAKE_CONTENT)
string(REGEX MATCH "project\\(libprojectM[^)]*VERSION ([0-9]+)\\.([0-9]+)\\.([0-9]+)" _ "${PROJECTM_CMAKE_CONTENT}")
if(NOT CMAKE_MATCH_3)
    message(FATAL_ERROR "Couldn't read libprojectM's version from its CMakeLists.txt.")
endif()
set(CMAKE_PROJECT_VERSION "${CMAKE_MATCH_1}.${CMAKE_MATCH_2}.${CMAKE_MATCH_3}")
set(CMAKE_PROJECT_VERSION_MAJOR ${CMAKE_MATCH_1})
set(CMAKE_PROJECT_VERSION_MINOR ${CMAKE_MATCH_2})
set(CMAKE_PROJECT_VERSION_PATCH ${CMAKE_MATCH_3})

add_subdirectory(${PROJECTM_SOURCE_DIR} ${CMAKE_CURRENT_BINARY_DIR}/projectm_build)

# hlslparser (transpiles every preset's HLSL shaders to GLSL on each preset load) builds a fresh
# std::locale("C") for every float literal it parses or prints. In libc++ constructing a *named*
# locale re-initialises all its facets (time_get format analysis, getenv, allocations), which
# profiled at a large share of each preset switch's GL-thread stall. std::locale::classic() is
# the same "C" locale as a shared static instance. Patched into a build-dir copy so the vendored
# submodule stays untouched.
set(HLSL_ENGINE_SRC ${PROJECTM_SOURCE_DIR}/vendor/hlslparser/src/Engine.cpp)
set(HLSL_ENGINE_PATCHED ${CMAKE_CURRENT_BINARY_DIR}/hlslparser_patched/Engine.cpp)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${HLSL_ENGINE_SRC})
file(READ ${HLSL_ENGINE_SRC} HLSL_ENGINE_CONTENT)
string(FIND "${HLSL_ENGINE_CONTENT}" "std::locale(\"C\")" HLSL_LOCALE_POS)
if(HLSL_LOCALE_POS EQUAL -1)
    message(FATAL_ERROR "hlslparser Engine.cpp no longer uses std::locale(\"C\"); revisit this patch.")
endif()
string(REPLACE "std::locale(\"C\")" "std::locale::classic()" HLSL_ENGINE_CONTENT "${HLSL_ENGINE_CONTENT}")
file(WRITE ${HLSL_ENGINE_PATCHED} "${HLSL_ENGINE_CONTENT}")
get_target_property(HLSL_SOURCES hlslparser SOURCES)
list(TRANSFORM HLSL_SOURCES REPLACE "^(.*/)?src/Engine\\.cpp$" ${HLSL_ENGINE_PATCHED})
set_property(TARGET hlslparser PROPERTY SOURCES ${HLSL_SOURCES})

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
