# Keep the GLSL in a source file and embed it at configure time. No runtime search/replace of
# projectM's generated shaders: the renderer compiles exactly the shader reviewed and tested here.
add_configure_dependency(${CMAKE_CURRENT_LIST_DIR}/MotionVectors.vert)
file(READ ${CMAKE_CURRENT_LIST_DIR}/MotionVectors.vert MOTION_VECTOR_VERTEX_SHADER)
configure_file(${CMAKE_CURRENT_LIST_DIR}/MotionVectorShader.hpp.in
    ${CMAKE_CURRENT_BINARY_DIR}/milkdrop_generated/MotionVectorShader.hpp @ONLY)
target_include_directories(MilkdropPreset PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/milkdrop_generated)

patch_projectm_source(MilkdropPreset src/libprojectM/MilkdropPreset/MotionVectors.cpp
    [=[#include "MilkdropStaticShaders.hpp"]=]
    [=[#include "MilkdropStaticShaders.hpp"
#include "MotionVectorShader.hpp"]=]
    [=[*presetPerFrameContext.mv_a < 0.0001f]=]
    [=[*presetPerFrameContext.mv_a < 0.001f]=]
    [=[    // Tweaked this a bit to ensure lines are always at least a bit more than 1px long.
    // Line smoothing makes some of them disappear otherwise.
    float const inverseWidth = 1.25f / static_cast<float>(m_presetState.renderContext.viewportSizeX);
    float const inverseHeight = 1.25f / static_cast<float>(m_presetState.renderContext.viewportSizeY);
    float const minimumLength = sqrtf(inverseWidth * inverseWidth + inverseHeight * inverseHeight);]=]
    [=[    // BeatDrop's min_len = 1 / render-texture width.
    float const minimumLength = 1.0f / static_cast<float>(motionTexture->Width());]=]
    [=[    m_motionVectorMesh.SetVertexCount(static_cast<std::size_t>(countX + 1) * 2); // countX + 1 lines for each grid row, 2 vertices each.]=]
    [=[    m_motionVectorMesh.SetVertexCount(static_cast<std::size_t>(countX) * 2);]=]
    [=[staticShaders->GetPresetMotionVectorsVertexShader()]=]
    [=[milkdrop::MotionVectorVertexShader]=]
    [=[            // Draw a row of lines.
            m_motionVectorMesh.Update();
            m_motionVectorMesh.Draw();]=]
    [=[            // Match DrawPrimitiveUP(..., n / 2, ...): draw only the valid line pairs.
            // Mesh caches its index count, so refresh it when mv_x or mv_dx changes.
            if (vertex == 0)
            {
                continue;
            }
            auto& indices = m_motionVectorMesh.Indices();
            indices.Resize(static_cast<std::size_t>(vertex));
            indices.MakeContinuous();
            m_motionVectorMesh.Update();
            m_motionVectorMesh.Draw();]=]
)
