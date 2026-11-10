# Batch compatible fills without reordering borders or texture operations. C++14 only.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[    Renderer::Mesh m_fillMesh;]=]
    [=[    Renderer::Mesh m_batchMesh;
    Renderer::Mesh m_fillMesh;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[    , m_fillMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)]=]
    [=[    , m_batchMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)
    , m_fillMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)]=]
    [=[    m_fillMesh.SetVertexCount(102);]=]
    [=[    m_batchMesh.SetRenderPrimitiveType(Renderer::Mesh::PrimitiveType::Triangles);
    m_fillMesh.SetVertexCount(102);]=]
    [=[    for (int instance = 0; instance < m_instances; instance++)]=]
    [=[    // Only borderless untextured fills are deferred. Preserve triangle/instance order,
    // and flush before any state change, textured instance, or visible border.
    auto& batchVertices = m_batchMesh.Vertices().Get();
    auto& batchColors = m_batchMesh.Colors().Get();
    auto& batchIndices = m_batchMesh.Indices().Get();
    batchVertices.clear();
    batchColors.clear();
    batchIndices.clear();
    bool batchAdditive = false;
    const auto flushBatch = [&]() {
        if (batchIndices.empty()) return;
        Renderer::BlendMode::SetBlendFunction(Renderer::BlendMode::Function::SourceAlpha,
            batchAdditive ? Renderer::BlendMode::Function::One : Renderer::BlendMode::Function::OneMinusSourceAlpha);
        auto shader = m_presetState.untexturedShader.lock();
        shader->Bind();
        shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
        m_batchMesh.Update();
        m_batchMesh.Draw();
        batchVertices.clear();
        batchColors.clear();
        batchIndices.clear();
    };

    for (int instance = 0; instance < m_instances; instance++)]=]
    [=[        // Additive Drawing or Overwrite]=]
    [=[        const bool additive = static_cast<int>(*m_perFrameContext.additive) != 0;
        const bool batchable = static_cast<int>(*m_perFrameContext.textured) == 0 &&
            !(*m_perFrameContext.border_a > 0.0001f);
        // Keep scratch storage bounded even if a preset requests unusually many instances.
        // This is a batch size, not a limit on how many shapes the preset executes.
        constexpr std::size_t maxBatchVertices = 128 * 102;
        if (!batchable || additive != batchAdditive || batchVertices.size() >= maxBatchVertices) flushBatch();
        batchAdditive = additive;
        // Empty untextured fills have no rasterized fragments; equations already ran.
        if (batchable && *m_perFrameContext.rad == 0.0) continue;

        // Additive Drawing or Overwrite]=]
    [=[        m_fillMesh.SetUseUV(]=]
    [=[        if (batchable)
        {
            const auto base = static_cast<uint32_t>(batchVertices.size());
            for (int i = 0; i < sides + 2; ++i)
            {
                batchVertices.push_back(vertexData[i]);
                batchColors.push_back(colorData[i]);
            }
            for (int i = 1; i <= sides; ++i)
            {
                batchIndices.push_back(base);
                batchIndices.push_back(base + i);
                batchIndices.push_back(base + i + 1);
            }
            continue;
        }

        m_fillMesh.SetUseUV(]=]
    [=[    Renderer::Mesh::Unbind();]=]
    [=[    flushBatch();

    Renderer::Mesh::Unbind();]=]
)
