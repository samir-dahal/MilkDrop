# Compatible shape-fill and additive-border batching, inspired by projectM PR #1044.
# Keep the app's color packing, sampler behavior, per-instance equations and thick flags.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[    Renderer::Mesh m_fillMesh;]=]
    [=[    Renderer::Mesh m_batchMesh;
    Renderer::Mesh m_borderBatchMesh;
    Renderer::Mesh m_fillMesh;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[    , m_fillMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)]=]
    [=[    , m_batchMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)
    , m_borderBatchMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)
    , m_fillMesh(Renderer::VertexBufferUsage::StreamDraw, true, false)]=]
    [=[    m_fillMesh.SetVertexCount(102);]=]
    [=[    m_batchMesh.SetRenderPrimitiveType(Renderer::Mesh::PrimitiveType::Triangles);
    m_borderBatchMesh.SetRenderPrimitiveType(Renderer::Mesh::PrimitiveType::Lines);
    m_fillMesh.SetVertexCount(102);]=]
    [=[    for (int instance = 0; instance < m_instances; instance++)]=]
    [=[    auto& vertices = m_batchMesh.Vertices().Get();
    auto& colors = m_batchMesh.Colors().Get();
    auto& batchUVs = m_batchMesh.UVs().Get();
    auto& indices = m_batchMesh.Indices().Get();
    auto& borders = m_borderBatchMesh.Vertices().Get();
    auto& borderColors = m_borderBatchMesh.Colors().Get();
    vertices.clear(); colors.clear(); batchUVs.clear(); indices.clear();
    borders.clear(); borderColors.clear();
    bool batchAdditive = false, batchTextured = false;
    bool imageAspectKnown = false;
    float imageAspect = 1.0f;
    const auto bindTexture = [&]() {
            auto shader = m_presetState.texturedShader.lock();
            shader->Bind();
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformInt("texture_sampler", 0);

            // Textured shape, either main texture or texture from "image" key
            auto textureAspectY = m_presetState.renderContext.aspectY;
            // Reset for every instance: the shared shader must not inherit a named
            // image's alpha mode when the next shape uses feedback or a missing image.
            shader->SetUniformFloat("milkdrop_image_alpha", 0.0f);
            if (m_image.empty())
            {
                assert(!m_presetState.mainTexture.expired());
                m_presetState.mainTexture.lock()->Bind(0);
            }
            else
            {
                auto desc = m_presetState.renderContext.textureManager->GetTexture(m_image);
                if (!desc.Empty())
                {
                    desc.Bind(0, *shader);
                    textureAspectY = 1.0f;
                    shader->SetUniformFloat("milkdrop_image_alpha", 1.0f);
                }
                else
                {
                    // No texture found, fall back to main texture.
                    assert(!m_presetState.mainTexture.expired());
                    m_presetState.mainTexture.lock()->Bind(0);
                }
            }

            // Match the sampler state left by BeatDrop's preceding passes:
            // blur clamps; shader warp restores repeat; fixed warp uses wrap.
            const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
                (shaderWarp || *perFrameContext.wrap > 0.0001));
            m_textureSampler.WrapMode(repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
            m_textureSampler.Bind(0);

            return textureAspectY;
    };
    bool plainUniformsSet = false;
    const auto bindPlain = [&]() {
        auto shader = m_presetState.untexturedShader.lock();
        shader->Bind();
        if (!plainUniformsSet)
        {
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformFloat("vertex_point_size", 1.0f);
            plainUniformsSet = true;
        }
    };
    const auto flushBatch = [&]() {
        if (indices.empty()) return;
        Renderer::BlendMode::SetBlendFunction(Renderer::BlendMode::Function::SourceAlpha,
            batchAdditive ? Renderer::BlendMode::Function::One : Renderer::BlendMode::Function::OneMinusSourceAlpha);
        if (batchTextured) bindTexture(); else bindPlain();
        m_batchMesh.SetUseUV(batchTextured);
        m_batchMesh.Update(); m_batchMesh.Draw();
        glBindTexture(GL_TEXTURE_2D, 0); Renderer::Sampler::Unbind(0);
        if (!borders.empty())
        {
            bindPlain();
            glLineWidth(1);
#ifndef USE_GLES
            glEnable(GL_LINE_SMOOTH);
#endif
            m_borderBatchMesh.Indices().Resize(borders.size());
            m_borderBatchMesh.Indices().MakeContinuous();
            m_borderBatchMesh.Update(); m_borderBatchMesh.Draw();
        }
        vertices.clear(); colors.clear(); batchUVs.clear(); indices.clear();
        borders.clear(); borderColors.clear();
    };

    for (int instance = 0; instance < m_instances; instance++)]=]
    [=[        // Additive Drawing or Overwrite]=]
    [=[        const bool additive = static_cast<int>(*m_perFrameContext.additive) != 0;
        const bool textured = static_cast<int>(*m_perFrameContext.textured) != 0;
        // Bound temporary storage without limiting preset instance/equation counts.
        if (additive != batchAdditive || textured != batchTextured ||
            vertices.size() >= 128 * 102 || borders.size() >= 128 * 100 * 8) flushBatch();
        batchAdditive = additive; batchTextured = textured;
        if (*m_perFrameContext.rad == 0.0 && !(*m_perFrameContext.border_a > 0.0001f)) continue;

        // Additive Drawing or Overwrite]=]
    [=[        if (m_fillMesh.UseUV())
        {
            auto shader = m_presetState.texturedShader.lock();
            shader->Bind();
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformInt("texture_sampler", 0);

            // Textured shape, either main texture or texture from "image" key
            auto textureAspectY = m_presetState.renderContext.aspectY;
            // Reset for every instance: the shared shader must not inherit a named
            // image's alpha mode when the next shape uses feedback or a missing image.
            shader->SetUniformFloat("milkdrop_image_alpha", 0.0f);
            if (m_image.empty())
            {
                assert(!m_presetState.mainTexture.expired());
                m_presetState.mainTexture.lock()->Bind(0);
            }
            else
            {
                auto desc = m_presetState.renderContext.textureManager->GetTexture(m_image);
                if (!desc.Empty())
                {
                    desc.Bind(0, *shader);
                    textureAspectY = 1.0f;
                    shader->SetUniformFloat("milkdrop_image_alpha", 1.0f);
                }
                else
                {
                    // No texture found, fall back to main texture.
                    assert(!m_presetState.mainTexture.expired());
                    m_presetState.mainTexture.lock()->Bind(0);
                }
            }

            // Match the sampler state left by BeatDrop's preceding passes:
            // blur clamps; shader warp restores repeat; fixed warp uses wrap.
            const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
                (shaderWarp || *perFrameContext.wrap > 0.0001));
            m_textureSampler.WrapMode(repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
            m_textureSampler.Bind(0);

            auto& uvs = m_fillMesh.UVs();

            uvs[0] = Renderer::TextureUV(0.5f, 0.5f);

            for (int i = 1; i < sides + 1; i++)
            {
                const float cornerProgress = static_cast<float>(i - 1) / static_cast<float>(sides);
                const float angle = cornerProgress * pi * 2.0f + static_cast<float>(*m_perFrameContext.tex_ang) + pi * 0.25f;

                uvs[i] = Renderer::TextureUV(0.5f + 0.5f * cosf(angle) / static_cast<float>(*m_perFrameContext.tex_zoom) * textureAspectY,
                                             1.0f - (0.5f - 0.5f * sinf(angle) / static_cast<float>(*m_perFrameContext.tex_zoom))); // Vertical flip required!
            }

            uvs[sides + 1] = uvs[1];
        }
        else
        {
            // Untextured (creates a color gradient: center=r/g/b/a to border=r2/b2/g2/a2)
            auto shader = m_presetState.untexturedShader.lock();
            shader->Bind();
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
        }

]=]
    [=[        if (textured)
        {
            if (!imageAspectKnown) { imageAspect = bindTexture(); imageAspectKnown = true; }
            auto& uvs = m_fillMesh.UVs();

            uvs[0] = Renderer::TextureUV(0.5f, 0.5f);

            for (int i = 1; i < sides + 1; i++)
            {
                const float cornerProgress = static_cast<float>(i - 1) / static_cast<float>(sides);
                const float angle = cornerProgress * pi * 2.0f + static_cast<float>(*m_perFrameContext.tex_ang) + pi * 0.25f;

                uvs[i] = Renderer::TextureUV(0.5f + 0.5f * cosf(angle) / static_cast<float>(*m_perFrameContext.tex_zoom) * imageAspect,
                                             1.0f - (0.5f - 0.5f * sinf(angle) / static_cast<float>(*m_perFrameContext.tex_zoom))); // Vertical flip required!
            }

            uvs[sides + 1] = uvs[1];
        }

]=]
    [=[        m_fillMesh.Indices().Resize(sides + 2);
        m_fillMesh.Indices().MakeContinuous();
        m_fillMesh.Update();
        m_fillMesh.Draw();

        glBindTexture(GL_TEXTURE_2D, 0);
        Renderer::Sampler::Unbind(0);

]=]
    [=[        const auto base = static_cast<uint32_t>(vertices.size());
        for (int i = 0; i < sides + 2; ++i)
        {
            vertices.push_back(vertexData[i]); colors.push_back(colorData[i]);
            if (textured) batchUVs.push_back(m_fillMesh.UVs()[i]);
        }
        for (int i = 1; i <= sides; ++i)
        {
            indices.push_back(base); indices.push_back(base + i); indices.push_back(base + i + 1);
        }

]=]
    [=[        if (*m_perFrameContext.border_a > 0.0001f)
        {]=]
    [=[        if (*m_perFrameContext.border_a > 0.0001f)
        {
            // Source-over borders must stay between their own fill and the next fill.
            if (!additive) flushBatch();]=]
    [=[                m_outlineMesh.Update();
                m_outlineMesh.Draw();]=]
    [=[                if (additive)
                {
                    const Renderer::Color color(packedChannel(*m_perFrameContext.border_r),
                        packedChannel(*m_perFrameContext.border_g), packedChannel(*m_perFrameContext.border_b),
                        packedChannel(*m_perFrameContext.border_a));
                    for (int i = 0; i < sides; ++i)
                    {
                        borders.push_back(points[i]); borders.push_back(points[(i + 1) % sides]);
                        borderColors.push_back(color); borderColors.push_back(color);
                    }
                }
                else
                {
                    m_outlineMesh.Update(); m_outlineMesh.Draw();
                }]=]
    [=[            auto shader = m_presetState.untexturedShader.lock();
            shader->Bind();
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformFloat("vertex_point_size", 1.0f);]=]
    [=[            bindPlain();]=]
    [=[    Renderer::Mesh::Unbind();]=]
    [=[    flushBatch();

    Renderer::Mesh::Unbind();]=]
)

# Keep the proven additive batching path. Both paths still handle per-instance
# blend changes; this dispatch never skips equations, instances or memory writes.
projectm_patched_path(MilkdropPreset "${PROJECTM_SOURCE_DIR}/src/libprojectM/MilkdropPreset/CustomShape.cpp" SHAPE_BATCH_SOURCE)
file(READ "${SHAPE_BATCH_SOURCE}" SHAPE_BATCH_CONTENT)
string(FIND "${SHAPE_BATCH_CONTENT}" "void CustomShape::Draw(" SHAPE_DRAW_START)
string(FIND "${SHAPE_BATCH_CONTENT}" "\n} // namespace MilkdropPreset" SHAPE_DRAW_END)
if(SHAPE_DRAW_START EQUAL -1 OR SHAPE_DRAW_END EQUAL -1)
    message(FATAL_ERROR "CustomShape draw boundaries changed; revisit shape streaming.")
endif()
math(EXPR SHAPE_DRAW_LENGTH "${SHAPE_DRAW_END} - ${SHAPE_DRAW_START}")
string(SUBSTRING "${SHAPE_BATCH_CONTENT}" ${SHAPE_DRAW_START} ${SHAPE_DRAW_LENGTH} SHAPE_ADDITIVE_DRAW)
string(REPLACE "void CustomShape::Draw(" "void CustomShape::DrawAdditive(" SHAPE_ADDITIVE_DRAW "${SHAPE_ADDITIVE_DRAW}")

# Upload source-over fills/borders together, retaining their original draw order.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[#include <projectm-eval.h>]=]
    [=[#include <projectm-eval.h>

#include <vector>]=]
    [=[    Renderer::Mesh m_batchMesh;
    Renderer::Mesh m_borderBatchMesh;
    Renderer::Mesh m_fillMesh;]=]
    [=[    struct ShapeDrawRange
    {
        GLenum primitive;
        uint32_t first;
        uint32_t count;
        bool textured;
    };
    std::vector<ShapeDrawRange> m_shapeDrawRanges;
    Renderer::Mesh m_batchMesh;
    Renderer::Mesh m_borderBatchMesh;
    Renderer::Mesh m_fillMesh;]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[    auto& vertices = m_batchMesh.Vertices().Get();
    auto& colors = m_batchMesh.Colors().Get();
    auto& batchUVs = m_batchMesh.UVs().Get();
    auto& indices = m_batchMesh.Indices().Get();
    auto& borders = m_borderBatchMesh.Vertices().Get();
    auto& borderColors = m_borderBatchMesh.Colors().Get();
    vertices.clear(); colors.clear(); batchUVs.clear(); indices.clear();
    borders.clear(); borderColors.clear();
    bool batchAdditive = false, batchTextured = false;
    bool imageAspectKnown = false;
    float imageAspect = 1.0f;
    const auto bindTexture = [&]() {
            auto shader = m_presetState.texturedShader.lock();
            shader->Bind();
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformInt("texture_sampler", 0);

            // Textured shape, either main texture or texture from "image" key
            auto textureAspectY = m_presetState.renderContext.aspectY;
            // Reset for every instance: the shared shader must not inherit a named
            // image's alpha mode when the next shape uses feedback or a missing image.
            shader->SetUniformFloat("milkdrop_image_alpha", 0.0f);
            if (m_image.empty())
            {
                assert(!m_presetState.mainTexture.expired());
                m_presetState.mainTexture.lock()->Bind(0);
            }
            else
            {
                auto desc = m_presetState.renderContext.textureManager->GetTexture(m_image);
                if (!desc.Empty())
                {
                    desc.Bind(0, *shader);
                    textureAspectY = 1.0f;
                    shader->SetUniformFloat("milkdrop_image_alpha", 1.0f);
                }
                else
                {
                    // No texture found, fall back to main texture.
                    assert(!m_presetState.mainTexture.expired());
                    m_presetState.mainTexture.lock()->Bind(0);
                }
            }

            // Match the sampler state left by BeatDrop's preceding passes:
            // blur clamps; shader warp restores repeat; fixed warp uses wrap.
            const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
                (shaderWarp || *perFrameContext.wrap > 0.0001));
            m_textureSampler.WrapMode(repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
            m_textureSampler.Bind(0);

            return textureAspectY;
    };
    bool plainUniformsSet = false;
    const auto bindPlain = [&]() {
        auto shader = m_presetState.untexturedShader.lock();
        shader->Bind();
        if (!plainUniformsSet)
        {
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformFloat("vertex_point_size", 1.0f);
            plainUniformsSet = true;
        }
    };
    const auto flushBatch = [&]() {
        if (indices.empty()) return;
        Renderer::BlendMode::SetBlendFunction(Renderer::BlendMode::Function::SourceAlpha,
            batchAdditive ? Renderer::BlendMode::Function::One : Renderer::BlendMode::Function::OneMinusSourceAlpha);
        if (batchTextured) bindTexture(); else bindPlain();
        m_batchMesh.SetUseUV(batchTextured);
        m_batchMesh.Update(); m_batchMesh.Draw();
        glBindTexture(GL_TEXTURE_2D, 0); Renderer::Sampler::Unbind(0);
        if (!borders.empty())
        {
            bindPlain();
            glLineWidth(1);
#ifndef USE_GLES
            glEnable(GL_LINE_SMOOTH);
#endif
            m_borderBatchMesh.Indices().Resize(borders.size());
            m_borderBatchMesh.Indices().MakeContinuous();
            m_borderBatchMesh.Update(); m_borderBatchMesh.Draw();
        }
        vertices.clear(); colors.clear(); batchUVs.clear(); indices.clear();
        borders.clear(); borderColors.clear();
    };

    for (int instance = 0; instance < m_instances; instance++)]=]
    [=[    auto& vertices = m_batchMesh.Vertices().Get();
    auto& colors = m_batchMesh.Colors().Get();
    auto& batchUVs = m_batchMesh.UVs().Get();
    auto& indices = m_batchMesh.Indices().Get();
    auto& borders = m_borderBatchMesh.Vertices().Get();
    auto& borderColors = m_borderBatchMesh.Colors().Get();
    vertices.clear(); colors.clear(); batchUVs.clear(); indices.clear();
    borders.clear(); borderColors.clear();
    auto& drawRanges = m_shapeDrawRanges;
    drawRanges.clear();
    uint32_t pendingFill = 0;
    const auto queueFill = [&]() {
        const auto end = static_cast<uint32_t>(indices.size());
        if (end > pendingFill) drawRanges.push_back({GL_TRIANGLES, pendingFill, end - pendingFill, true});
        pendingFill = end;
    };
    // Exact trig reuse for consecutive instances with the same polygon angles.
    // Equations still execute for every instance; no geometry or instance is omitted.
    int cachedSides = 0;
    float cachedAngle = 0.0f;
    float polygonCos[100], polygonSin[100];
    bool batchAdditive = false, batchTextured = false;
    bool imageAspectKnown = false;
    float imageAspect = 1.0f;
    const auto bindTexture = [&]() {
            auto shader = m_presetState.texturedShader.lock();
            shader->Bind();
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformInt("texture_sampler", 0);

            // Textured shape, either main texture or texture from "image" key
            auto textureAspectY = m_presetState.renderContext.aspectY;
            // Reset for every instance: the shared shader must not inherit a named
            // image's alpha mode when the next shape uses feedback or a missing image.
            shader->SetUniformFloat("milkdrop_image_alpha", 0.0f);
            if (m_image.empty())
            {
                assert(!m_presetState.mainTexture.expired());
                m_presetState.mainTexture.lock()->Bind(0);
            }
            else
            {
                auto desc = m_presetState.renderContext.textureManager->GetTexture(m_image);
                if (!desc.Empty())
                {
                    desc.Bind(0, *shader);
                    textureAspectY = 1.0f;
                    shader->SetUniformFloat("milkdrop_image_alpha", 1.0f);
                }
                else
                {
                    // No texture found, fall back to main texture.
                    assert(!m_presetState.mainTexture.expired());
                    m_presetState.mainTexture.lock()->Bind(0);
                }
            }

            // Match the sampler state left by BeatDrop's preceding passes:
            // blur clamps; shader warp restores repeat; fixed warp uses wrap.
            const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
                (shaderWarp || *perFrameContext.wrap > 0.0001));
            m_textureSampler.WrapMode(repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
            m_textureSampler.Bind(0);

            return textureAspectY;
    };
    bool plainUniformsSet = false;
    const auto bindPlain = [&]() {
        auto shader = m_presetState.untexturedShader.lock();
        shader->Bind();
        if (!plainUniformsSet)
        {
            shader->SetUniformMat4x4("vertex_transformation", PresetState::orthogonalProjection);
            shader->SetUniformFloat("vertex_point_size", 1.0f);
            plainUniformsSet = true;
        }
    };
    const auto flushBatch = [&]() {
        if (indices.empty()) return;
        Renderer::BlendMode::SetBlendFunction(Renderer::BlendMode::Function::SourceAlpha,
            batchAdditive ? Renderer::BlendMode::Function::One : Renderer::BlendMode::Function::OneMinusSourceAlpha);
        if (batchTextured) bindTexture(); else bindPlain();
        m_batchMesh.SetUseUV(batchTextured);
        if (!batchAdditive) queueFill();
        m_batchMesh.Update();
        // Keep the existing compact additive batch path; ordered ranges are only
        // needed when source-over borders must interleave with the fills.
        if (batchAdditive) m_batchMesh.Draw();
        // Update leaves this VAO bound. Keep it bound across ordered draw ranges.
        bool activeTextured = batchTextured;
        glLineWidth(1);
#ifndef USE_GLES
        glEnable(GL_LINE_SMOOTH);
#endif
        for (const auto& range : drawRanges)
        {
            const bool useTexture = batchTextured && range.textured;
            if (useTexture != activeTextured)
            {
                if (useTexture) bindTexture(); else bindPlain();
                activeTextured = useTexture;
            }
            glDrawElements(range.primitive, static_cast<GLsizei>(range.count), GL_UNSIGNED_INT,
                reinterpret_cast<const void*>(static_cast<uintptr_t>(range.first) * sizeof(uint32_t)));
        }
        glBindTexture(GL_TEXTURE_2D, 0); Renderer::Sampler::Unbind(0);
        if (!borders.empty())
        {
            bindPlain();
            glLineWidth(1);
#ifndef USE_GLES
            glEnable(GL_LINE_SMOOTH);
#endif
            m_borderBatchMesh.Indices().Resize(borders.size());
            m_borderBatchMesh.Indices().MakeContinuous();
            m_borderBatchMesh.Update(); m_borderBatchMesh.Draw();
        }
        vertices.clear(); colors.clear(); batchUVs.clear(); indices.clear();
        borders.clear(); borderColors.clear();
        drawRanges.clear(); pendingFill = 0;
    };

    for (int instance = 0; instance < m_instances; instance++)]=]
    [=[        if (*m_perFrameContext.border_a > 0.0001f)
        {
            // Source-over borders must stay between their own fill and the next fill.
            if (!additive) flushBatch();]=]
    [=[        if (*m_perFrameContext.border_a > 0.0001f)
        {
            // Queue the fill before its source-over borders; never reorder instances.
            if (!additive) queueFill();]=]
    [=[                if (additive)
                {
                    const Renderer::Color color(packedChannel(*m_perFrameContext.border_r),
                        packedChannel(*m_perFrameContext.border_g), packedChannel(*m_perFrameContext.border_b),
                        packedChannel(*m_perFrameContext.border_a));
                    for (int i = 0; i < sides; ++i)
                    {
                        borders.push_back(points[i]); borders.push_back(points[(i + 1) % sides]);
                        borderColors.push_back(color); borderColors.push_back(color);
                    }
                }
                else
                {
                    m_outlineMesh.Update(); m_outlineMesh.Draw();
                }]=]
    [=[                if (additive)
                {
                    const Renderer::Color color(packedChannel(*m_perFrameContext.border_r),
                        packedChannel(*m_perFrameContext.border_g), packedChannel(*m_perFrameContext.border_b),
                        packedChannel(*m_perFrameContext.border_a));
                    for (int i = 0; i < sides; ++i)
                    {
                        borders.push_back(points[i]); borders.push_back(points[(i + 1) % sides]);
                        borderColors.push_back(color); borderColors.push_back(color);
                    }
                }
                else
                {
                    const auto first = static_cast<uint32_t>(indices.size());
                    const auto start = static_cast<uint32_t>(vertices.size());
                    const Renderer::Color borderColor(packedChannel(*m_perFrameContext.border_r),
                        packedChannel(*m_perFrameContext.border_g), packedChannel(*m_perFrameContext.border_b),
                        packedChannel(*m_perFrameContext.border_a));
                    for (int i = 0; i < sides; ++i)
                    {
                        vertices.push_back(points[i]); colors.push_back(borderColor);
                        if (batchTextured) batchUVs.push_back(Renderer::TextureUV(0.0f, 0.0f));
                        indices.push_back(start + i);
                    }
                    drawRanges.push_back({GL_LINE_LOOP, first, static_cast<uint32_t>(sides), false});
                    pendingFill = static_cast<uint32_t>(indices.size());
                }]=]
    [=[            bindPlain();

            glVertexAttrib4f(1,]=]
    [=[            // Shader selection is deferred to the ordered draw stream.

            glVertexAttrib4f(1,]=]
    [=[        // Additive Drawing or Overwrite
        Renderer::BlendMode::SetBlendFunction(Renderer::BlendMode::Function::SourceAlpha,
                                              static_cast<int>(*m_perFrameContext.additive) != 0
                                                  ? Renderer::BlendMode::Function::One
                                                  : Renderer::BlendMode::Function::OneMinusSourceAlpha);]=]
    [=[        // Blend state is applied once when the ordered stream is flushed.]=]
    [=[        for (int i = 1; i < sides + 1; i++)
        {
            const float cornerProgress = static_cast<float>(i - 1) / static_cast<float>(sides);
            const float angle = cornerProgress * pi * 2.0f + static_cast<float>(*m_perFrameContext.ang) + pi * 0.25f;]=]
    [=[        const float polygonAngle = static_cast<float>(*m_perFrameContext.ang);
        if (!additive && (cachedSides != sides || cachedAngle != polygonAngle))
        {
            for (int i = 0; i < sides; ++i)
            {
                const float cornerProgress = static_cast<float>(i) / static_cast<float>(sides);
                const float angle = cornerProgress * pi * 2.0f + polygonAngle + pi * 0.25f;
                polygonCos[i] = cosf(angle);
                polygonSin[i] = sinf(angle);
            }
            cachedSides = sides; cachedAngle = polygonAngle;
        }
        for (int i = 1; i < sides + 1; i++)
        {
            const float cornerProgress = static_cast<float>(i - 1) / static_cast<float>(sides);
            const float angle = cornerProgress * pi * 2.0f + polygonAngle + pi * 0.25f;]=]
    [=[* cosf(angle) * m_presetState.renderContext.aspectY]=]
    [=[* (additive ? cosf(angle) : polygonCos[i - 1]) * m_presetState.renderContext.aspectY]=]
    [=[static_cast<float>(*m_perFrameContext.rad) * sinf(angle)]=]
    [=[static_cast<float>(*m_perFrameContext.rad) * (additive ? sinf(angle) : polygonSin[i - 1])]=]
    [=[            m_outlineMesh.Indices().Resize(sides);
            m_outlineMesh.Indices().MakeContinuous();]=]
    [=[            // Outline positions are copied into the shared stream below.]=]
    [=[            glVertexAttrib4f(1,
                             packedChannel(*m_perFrameContext.border_r),
                             packedChannel(*m_perFrameContext.border_g),
                             packedChannel(*m_perFrameContext.border_b),
                             packedChannel(*m_perFrameContext.border_a));
            glLineWidth(1);
#ifndef USE_GLES
            glEnable(GL_LINE_SMOOTH);
#endif]=]
    [=[            // Border colors and raster state are supplied by the stream.]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[    struct ShapeDrawRange]=]
    [=[    void DrawAdditive(const PerFrameContext& perFrameContext, bool shaderWarp);

    struct ShapeDrawRange]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[void CustomShape::Draw(const PerFrameContext& perFrameContext, bool shaderWarp)
{]=]
    [=[void CustomShape::Draw(const PerFrameContext& perFrameContext, bool shaderWarp)
{
    if (m_additive)
    {
        DrawAdditive(perFrameContext, shaderWarp);
        return;
    }]=]
    [=[} // namespace MilkdropPreset]=]
    "${SHAPE_ADDITIVE_DRAW}\n} // namespace MilkdropPreset"
)
