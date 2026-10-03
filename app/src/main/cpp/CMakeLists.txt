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

get_filename_component(PROJECTM_SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/projectm ABSOLUTE)

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

# Build-time patches to projectM sources, applied to build-dir copies so the vendored submodule
# stays untouched.

# Where the patched copy of a target's source goes: inside the target's own binary dir, which keeps
# object file paths short (sources outside it get long "__/__/..." paths that can pass MAX_PATH).
function(projectm_patched_path TARGET SOURCE_ABS OUT_VAR)
    get_target_property(TARGET_SOURCE_DIR ${TARGET} SOURCE_DIR)
    get_target_property(TARGET_BINARY_DIR ${TARGET} BINARY_DIR)
    file(RELATIVE_PATH SOURCE_REL ${TARGET_SOURCE_DIR} ${SOURCE_ABS})
    set(${OUT_VAR} ${TARGET_BINARY_DIR}/patched/${SOURCE_REL} PARENT_SCOPE)
endfunction()

# Re-runs the configure (and so the patch) when an original changes. Each file only once: Ninja
# rejects a configure dependency listed twice.
function(add_configure_dependency FILE_PATH)
    get_property(DEPENDS DIRECTORY PROPERTY CMAKE_CONFIGURE_DEPENDS)
    if(NOT FILE_PATH IN_LIST DEPENDS)
        set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${FILE_PATH})
    endif()
endfunction()

# Arguments after SRC_REL are FIND/REPLACE pairs, read one by one through ARGV<n> so semicolons in
# them survive. Every FIND must be present, so an upstream change fails the configure instead of
# silently dropping a patch.
function(patch_projectm_source TARGET SRC_REL)
    set(SRC ${PROJECTM_SOURCE_DIR}/${SRC_REL})
    projectm_patched_path(${TARGET} ${SRC} PATCHED)
    add_configure_dependency(${SRC})
    file(READ ${SRC} CONTENT)
    math(EXPR LAST "${ARGC} - 1")
    foreach(I RANGE 2 ${LAST} 2)
        math(EXPR J "${I} + 1")
        set(FIND "${ARGV${I}}")
        set(REPLACE "${ARGV${J}}")
        string(FIND "${CONTENT}" "${FIND}" POS)
        if(POS EQUAL -1)
            message(FATAL_ERROR "${SRC_REL} no longer contains \"${FIND}\"; revisit this patch.")
        endif()
        string(REPLACE "${FIND}" "${REPLACE}" CONTENT "${CONTENT}")
    endforeach()
    file(WRITE ${PATCHED} "${CONTENT}")

    # Point the target at the copy (a no-op if mirror_projectm_dir already did).
    get_target_property(TARGET_DIR ${TARGET} SOURCE_DIR)
    get_target_property(SOURCES ${TARGET} SOURCES)
    set(NEW_SOURCES)
    foreach(SOURCE IN LISTS SOURCES)
        get_filename_component(SOURCE_ABS ${SOURCE} ABSOLUTE BASE_DIR ${TARGET_DIR})
        if(SOURCE_ABS STREQUAL SRC)
            set(SOURCE ${PATCHED})
        endif()
        list(APPEND NEW_SOURCES ${SOURCE})
    endforeach()
    set_property(TARGET ${TARGET} PROPERTY SOURCES ${NEW_SOURCES})
endfunction()

# Copies all of a target's C++ sources under DIR_REL into the patched tree and builds those
# instead. Needed before patching a header: the sources include it with quotes, which resolves
# next to the including file before any include path, so a lone patched copy would be ignored.
function(mirror_projectm_dir TARGET DIR_REL)
    set(SRC_DIR ${PROJECTM_SOURCE_DIR}/${DIR_REL})
    get_target_property(TARGET_DIR ${TARGET} SOURCE_DIR)
    get_target_property(SOURCES ${TARGET} SOURCES)
    set(NEW_SOURCES)
    foreach(SOURCE IN LISTS SOURCES)
        get_filename_component(SOURCE_ABS ${SOURCE} ABSOLUTE BASE_DIR ${TARGET_DIR})
        cmake_path(IS_PREFIX SRC_DIR ${SOURCE_ABS} NORMALIZE IN_DIR)
        if(IN_DIR AND SOURCE_ABS MATCHES "\\.(cpp|hpp)$")
            projectm_patched_path(${TARGET} ${SOURCE_ABS} SOURCE)
            add_configure_dependency(${SOURCE_ABS})
            get_filename_component(SOURCE_DST_DIR ${SOURCE} DIRECTORY)
            file(MAKE_DIRECTORY ${SOURCE_DST_DIR})
            file(COPY_FILE ${SOURCE_ABS} ${SOURCE} ONLY_IF_DIFFERENT)
        endif()
        list(APPEND NEW_SOURCES ${SOURCE})
    endforeach()
    set_property(TARGET ${TARGET} PROPERTY SOURCES ${NEW_SOURCES})
    projectm_patched_path(${TARGET} ${SRC_DIR} DST_DIR)
    target_include_directories(${TARGET} BEFORE PUBLIC ${DST_DIR})
endfunction()

# hlslparser (transpiles every preset's HLSL shaders to GLSL on each preset load):
# - It builds a fresh std::locale("C") for every float literal it parses or prints. In libc++
#   constructing a *named* locale re-initialises all its facets (time_get format analysis, getenv,
#   allocations), which profiled at a large share of each preset switch's GL-thread stall.
#   std::locale::classic() is the same "C" locale as a shared static instance.
# - It reports parse errors (line and reason) with vprintf, which goes nowhere on Android; send
#   them to logcat instead.
patch_projectm_source(hlslparser vendor/hlslparser/src/Engine.cpp
    "std::locale(\"C\")" "std::locale::classic()"
    "void Log_ErrorArgList(" "#include <android/log.h>\nvoid Log_ErrorArgList("
    "vprintf( format, args );" "__android_log_vprint(ANDROID_LOG_WARN, \"MilkDropShader\", format, args);"
)
# Its errors only give a line number in projectM's internally rewritten source; add the source text
# at the error so the failing construct can be read straight from logcat.
patch_projectm_source(hlslparser vendor/hlslparser/src/HLSLTokenizer.cpp
    [=[Log_Error("%s(%d) : %s\n", m_fileName, m_lineNumber, buffer);]=]
    [=[const char* nearEnd = m_bufferPrevious;
    while (nearEnd < m_bufferEnd && *nearEnd != '\n' && nearEnd - m_bufferPrevious < 80) ++nearEnd;
    Log_Error("%s(%d) : %s, at: %.*s\n", m_fileName, m_lineNumber, buffer, int(nearEnd - m_bufferPrevious), m_bufferPrevious);]=]
)
# Parser fixes for shaders that compile in MilkDrop (DirectX) but not through hlslparser:
# - tanh/sinh/cosh are HLSL intrinsics it doesn't know (GLSL ES 3.0 has them under the same names).
# - It brackets every macro expansion, so with projectM's "#define aspect _c0" a preset declaring
#   its own "float aspect = aspect.x/aspect.y;" became "float (_c0) = ..." - a syntax error. A
#   plain name or member chain doesn't need brackets, only spaces (the preprocessor doesn't keep
#   the whitespace around the macro, so "return saturate" would become "returnsaturate").
# - It declared a variable before parsing its initialiser, so in that same line "_c0.y" read the
#   new scalar. GLSL starts the scope after the initialiser, so the generated code is then correct.
patch_projectm_source(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[INTRINSIC_FLOAT1_FUNCTION( "tan" ),]=]
    [=[INTRINSIC_FLOAT1_FUNCTION( "tan" ),
        INTRINSIC_FLOAT1_FUNCTION( "tanh" ),
        INTRINSIC_FLOAT1_FUNCTION( "sinh" ),
        INTRINSIC_FLOAT1_FUNCTION( "cosh" ),]=]
    [=[                sourcePreprocessed.append("(");
                sourcePreprocessed.append(m_macros[i]->value);
                sourcePreprocessed.append(")");]=]
    [=[                const char* value = m_macros[i]->value;
                bool isPlainName = isalpha(value[0]) || value[0] == '_';
                for (const char* c = value; *c != 0 && isPlainName; ++c)
                {
                    isPlainName = isalnum(*c) || *c == '_' || *c == '.';
                }
                sourcePreprocessed.append(isPlainName ? " " : "(");
                sourcePreprocessed.append(value);
                sourcePreprocessed.append(isPlainName ? " " : ")");]=]
    [=[        DeclareVariable( declaration2->name, declaration2->type );

        // Handle option assignment of the declared variables(s).
        if (!ParseDeclarationAssignment( declaration2 )) {
            return false;
        }]=]
    [=[        // Handle option assignment of the declared variables(s).
        if (!ParseDeclarationAssignment( declaration2 )) {
            return false;
        }

        DeclareVariable( declaration2->name, declaration2->type );]=]
)

# MilkDrop 3 raised the custom shape and custom wave slots from 4 each to 16; presets using slots
# 4-15 lose those shapes/waves without this. Only MilkdropPreset uses these constants.
mirror_projectm_dir(MilkdropPreset src/libprojectM/MilkdropPreset)
patch_projectm_source(MilkdropPreset src/libprojectM/MilkdropPreset/Constants.hpp
    "CustomWaveformCount = 4" "CustomWaveformCount = 16"
    "CustomShapeCount = 4" "CustomShapeCount = 16"
)

# projectM silently falls back to a default shader (or none) when a preset's warp/composite shader
# fails to translate or compile; the reason is only printed in MILKDROP_PRESET_DEBUG builds, to
# stderr. Log it to logcat, without the full shader source projectM appends to translation errors.
set(SHADER_ERROR_LOG [=[__android_log_print(ANDROID_LOG_WARN, "MilkDropShader", "%s shader: %s", STAGE, ex.message().substr(0, ex.message().find("\nSource:")).c_str())]=])
set(SHADER_STAGES composite warp)
set(SHADER_STAGE_FILES FinalComposite PerPixelMesh)
foreach(STAGE FILE_NAME IN ZIP_LISTS SHADER_STAGES SHADER_STAGE_FILES)
    string(REPLACE "STAGE" "\"${STAGE}\"" STAGE_LOG "${SHADER_ERROR_LOG}")
    patch_projectm_source(MilkdropPreset src/libprojectM/MilkdropPreset/${FILE_NAME}.cpp
        "#include \"${FILE_NAME}.hpp\"" "#include \"${FILE_NAME}.hpp\"\n#include <android/log.h>"
        "(void)ex; // silence unused parameter warning" "${STAGE_LOG};"
    )
endforeach()

find_library(log-lib log)
target_link_libraries(projectM PRIVATE ${log-lib})

# projectM's build appends a "d" debug postfix and gives the playlist library an
# "-4-playlist" suffixed name; pin both to fixed names so ProjectMBridge's
# System.loadLibrary calls don't need to vary by build type.
set_target_properties(projectM PROPERTIES DEBUG_POSTFIX "")
set_target_properties(projectM_playlist PROPERTIES DEBUG_POSTFIX "" OUTPUT_NAME "projectM_playlist")

add_library(milkdropjni SHARED milkdrop_jni.cpp)
set_target_properties(milkdropjni PROPERTIES DEBUG_POSTFIX "")

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
