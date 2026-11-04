# Run after shader/parser patches, extending their build-directory copies.
function(patch_projectm_copy TARGET SRC_REL)
    projectm_patched_path(${TARGET} ${PROJECTM_SOURCE_DIR}/${SRC_REL} PATCHED)
    if(NOT EXISTS ${PATCHED})
        message(FATAL_ERROR "Expected mirrored projectM source: ${SRC_REL}")
    endif()
    file(READ ${PATCHED} CONTENT)
    math(EXPR LAST "${ARGC} - 1")
    foreach(I RANGE 2 ${LAST} 2)
        math(EXPR J "${I} + 1")
        string(FIND "${CONTENT}" "${ARGV${I}}" POSITION)
        if(POSITION EQUAL -1)
            message(FATAL_ERROR "${SRC_REL} changed; revisit the screen-dependent patch: ${ARGV${I}}")
        endif()
        string(REPLACE "${ARGV${I}}" "${ARGV${J}}" CONTENT "${CONTENT}")
    endforeach()
    file(WRITE ${PATCHED} "${CONTENT}")
endfunction()

target_include_directories(projectM_main PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_include_directories(MilkdropPreset PRIVATE ${CMAKE_CURRENT_LIST_DIR})

# Master owns a per-instance aspect flag but does not apply it to RenderContext yet.
patch_projectm_source(projectM_main src/libprojectM/ProjectM.cpp
    [=[#include "ProjectM.hpp"]=]
    [=[#include "ProjectM.hpp"
#include "ScreenAspect.hpp"]=]
    [=[    ctx.invAspectY = 1.0f / ctx.aspectY;]=]
    [=[    ctx.invAspectY = 1.0f / ctx.aspectY;
    milkdrop::ApplyScreenDependentAspect(ctx, !m_aspectCorrection);]=]
)

# Geometry and equations read effective aspect from RenderContext. Shader aspect
# remains physical; aspect_sd uses effective .xy multipliers and .zw inverses.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[#include "MilkdropShader.hpp"]=]
    [=[#include "MilkdropShader.hpp"
#include "ScreenAspect.hpp"]=]
    [=[    m_shader.SetUniformFloat4("_c0", {presetState.renderContext.aspectX,
                                      presetState.renderContext.aspectY,
                                      1.0f / presetState.renderContext.aspectX,
                                      1.0f / presetState.renderContext.aspectY});]=]
    [=[    const auto physicalAspect = milkdrop::PhysicalAspect(presetState.renderContext);
    m_shader.SetUniformFloat4("_c0", {physicalAspect.aspectX, physicalAspect.aspectY,
                                      physicalAspect.invAspectX, physicalAspect.invAspectY});]=]
    [=[    m_shader.SetUniformFloat4("_c1", {0.0,
                                      0.0,
                                      0.0,
                                      0.0});]=]
    [=[    m_shader.SetUniformFloat4("_c1", {presetState.renderContext.aspectX,
                                      presetState.renderContext.aspectY,
                                      presetState.renderContext.invAspectX,
                                      presetState.renderContext.invAspectY});]=]
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());]=]
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());
    fullSource.append("\n#define aspect_sd _c1\n");]=]
)

# Built-in waves bypassed RenderContext, deriving aspect directly from the viewport.
# Reset both axes so toggles and rotation cannot retain an old scale.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/WaveformMath.cpp
    [=[    if (presetState.renderContext.viewportSizeX > presetState.renderContext.viewportSizeY)
    {
        m_aspectY = static_cast<float>(presetState.renderContext.viewportSizeY) / static_cast<float>(presetState.renderContext.viewportSizeX);
    }
    else
    {
        m_aspectX = static_cast<float>(presetState.renderContext.viewportSizeX) / static_cast<float>(presetState.renderContext.viewportSizeY);
    }]=]
    [=[    m_aspectX = presetState.renderContext.aspectX;
    m_aspectY = presetState.renderContext.aspectY;]=]
)

# Legacy final compositing must not reintroduce a physical-aspect stretch in screen mode.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/VideoEcho.cpp
    [=[float const aspect = m_presetState.renderContext.viewportSizeX / static_cast<float>(m_presetState.renderContext.viewportSizeY * m_presetState.renderContext.invAspectY);]=]
    [=[float const aspect = (m_presetState.renderContext.aspectX == 1.0f && m_presetState.renderContext.aspectY == 1.0f)
        ? 1.0f : m_presetState.renderContext.viewportSizeX / static_cast<float>(m_presetState.renderContext.viewportSizeY * m_presetState.renderContext.invAspectY);]=]
)

# Invalidate cached radius/angle meshes on toggles without requiring a viewport resize.
foreach(CLASS IN ITEMS PerPixelMesh FinalComposite)
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/${CLASS}.hpp
        [=[    int m_viewportHeight{};]=]
        [=[    float m_meshAspectX{};
    float m_meshAspectY{};
    int m_viewportHeight{};]=]
    )
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/${CLASS}.cpp
        [=[m_viewportHeight == presetState.renderContext.viewportSizeY)]=]
        [=[m_viewportHeight == presetState.renderContext.viewportSizeY &&
             m_meshAspectX == presetState.renderContext.aspectX &&
             m_meshAspectY == presetState.renderContext.aspectY)]=]
    )
endforeach()
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.cpp
    [=[    const float aspectY = presetState.renderContext.aspectY;]=]
    [=[    const float aspectY = presetState.renderContext.aspectY;
    m_viewportWidth = presetState.renderContext.viewportSizeX;
    m_viewportHeight = presetState.renderContext.viewportSizeY;
    m_meshAspectX = aspectX;
    m_meshAspectY = aspectY;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/FinalComposite.cpp
    [=[    float const halfTexelWidth = 0.5f / static_cast<float>(presetState.renderContext.viewportSizeX);]=]
    [=[    m_viewportWidth = presetState.renderContext.viewportSizeX;
    m_viewportHeight = presetState.renderContext.viewportSizeY;
    m_meshAspectX = presetState.renderContext.aspectX;
    m_meshAspectY = presetState.renderContext.aspectY;
    float const halfTexelWidth = 0.5f / static_cast<float>(presetState.renderContext.viewportSizeX);]=]
)
