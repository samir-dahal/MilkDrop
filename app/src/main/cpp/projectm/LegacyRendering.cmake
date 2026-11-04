# Compatibility fixes applied after the existing patches to build-directory copies.
# Follow C/HLSL macro token boundaries; removing whitespace merged declarations
# such as "sampler sampler_manyfish;" into a single invalid identifier.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[value.append(m_tokenizer.getLastPos(true),
                             m_tokenizer.getCurrentPos() - m_tokenizer.getLastPos(true));]=]
    [=[value.append(m_tokenizer.getLastPos(false),
                             m_tokenizer.getCurrentPos() - m_tokenizer.getLastPos(false));]=]
    [=[const char * start = m_tokenizer.getLastPos(true);]=]
    [=[const char * start = m_tokenizer.getLastPos(false);]=]
    [=[valueProcessed.append(m_tokenizer.getLastPos(true),
                                      m_tokenizer.getCurrentPos() - m_tokenizer.getLastPos(true));]=]
    [=[valueProcessed.append(m_tokenizer.getLastPos(false),
                                      m_tokenizer.getCurrentPos() - m_tokenizer.getLastPos(false));]=]
    [=[    // Remove extra parenthesis
    if (value[0] == '(')
    {
        value.erase(value.length()-1, 1);
        value.erase(0, 1);
    }]=]
    [=[    // Preserve the author's parentheses, including deliberately ungrouped macros.]=]
    [=[                    if (macro->value == matchedMacro->name)]=]
    [=[                    const std::string aliasValue(macro->value);
                    const auto first = aliasValue.find_first_not_of(" \t\r\n");
                    const auto last = aliasValue.find_last_not_of(" \t\r\n");
                    if (first != std::string::npos &&
                        aliasValue.substr(first, last - first + 1) == matchedMacro->name)]=]
    [=[                    bool isPlainName = isalpha(value[0]) || value[0] == '_';
                    for (const char* c = value; *c != 0 && isPlainName; ++c)
                    {
                        isPlainName = isalnum(*c) || *c == '_' || *c == '.';
                    }
                    sourcePreprocessed.append(isPlainName ? " " : "(");
                    sourcePreprocessed.append(value);
                    sourcePreprocessed.append(isPlainName ? " " : ")");]=]
    [=[                    sourcePreprocessed.append(" ");
                    sourcePreprocessed.append(value);
                    sourcePreprocessed.append(" ");]=]
)

# Resolve aliases before discovering textures, so sampler macros and aliases to
# main/rand images produce the same declarations and bindings as literal names.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[    PrefixPlainSamplerNames(m_preprocessedCode);
    GetReferencedSamplers(m_preprocessedCode);
    PreprocessPresetShader(m_preprocessedCode);]=]
    [=[    PreprocessPresetShader(m_preprocessedCode);
    M4::Allocator allocator;
    M4::HLSLTree tree(&allocator);
    M4::HLSLParser parser(&allocator, &tree);
    std::string expanded;
    if (!parser.ApplyPreprocessor("", m_preprocessedCode.c_str(), m_preprocessedCode.size(), expanded))
        throw Renderer::ShaderException("Preset shader macro expansion failed.");
    m_preprocessedCode = std::move(expanded);
    PrefixPlainSamplerNames(m_preprocessedCode);
    GetReferencedSamplers(m_preprocessedCode);]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[#include <Renderer/Mesh.hpp>]=]
    [=[#include <Renderer/Mesh.hpp>
#include <Renderer/Sampler.hpp>]=]
    [=[    Renderer::Mesh m_fillMesh;]=]
    [=[    Renderer::Sampler m_textureSampler{GL_REPEAT, GL_LINEAR};
    Renderer::Mesh m_fillMesh;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[        colorData[0] = Renderer::Color::Modulo(Renderer::Color(static_cast<float>(*m_perFrameContext.r),
                                                               static_cast<float>(*m_perFrameContext.g),
                                                               static_cast<float>(*m_perFrameContext.b),
                                                               static_cast<float>(*m_perFrameContext.a)));

        colorData[1] = Renderer::Color::Modulo(Renderer::Color(static_cast<float>(*m_perFrameContext.r2),
                                                               static_cast<float>(*m_perFrameContext.g2),
                                                               static_cast<float>(*m_perFrameContext.b2),
                                                               static_cast<float>(*m_perFrameContext.a2)));]=]
    [=[        // D3DCOLOR packs the double-valued equation result before interpolation.
        // Keep truncation toward zero and byte wrapping, including negative values.
        // Floating modulo alone changes opacity on every recursive feedback pass.
        const auto packedChannel = [](double value) -> float {
            const double scaled = value * 255.0;
            if (!std::isfinite(scaled)) return 0.0f;
            double byte = std::fmod(std::trunc(scaled), 256.0);
            if (byte < 0.0) byte += 256.0;
            return static_cast<float>(byte / 255.0);
        };
        colorData[0] = Renderer::Color(packedChannel(*m_perFrameContext.r),
                                      packedChannel(*m_perFrameContext.g),
                                      packedChannel(*m_perFrameContext.b),
                                      packedChannel(*m_perFrameContext.a));
        colorData[1] = Renderer::Color(packedChannel(*m_perFrameContext.r2),
                                      packedChannel(*m_perFrameContext.g2),
                                      packedChannel(*m_perFrameContext.b2),
                                      packedChannel(*m_perFrameContext.a2));]=]
    [=[                             static_cast<float>(*m_perFrameContext.border_r),
                             static_cast<float>(*m_perFrameContext.border_g),
                             static_cast<float>(*m_perFrameContext.border_b),
                             static_cast<float>(*m_perFrameContext.border_a));]=]
    [=[                             packedChannel(*m_perFrameContext.border_r),
                             packedChannel(*m_perFrameContext.border_g),
                             packedChannel(*m_perFrameContext.border_b),
                             packedChannel(*m_perFrameContext.border_a));]=]
    [=[            auto textureAspectY = m_presetState.renderContext.aspectY;]=]
    [=[            auto textureAspectY = m_presetState.renderContext.aspectY;
            // Reset for every instance: the shared shader must not inherit a named
            // image's alpha mode when the next shape uses feedback or a missing image.
            shader->SetUniformFloat("milkdrop_image_alpha", 0.0f);]=]
    [=[                    textureAspectY = 1.0f;]=]
    [=[                    textureAspectY = 1.0f;
                    shader->SetUniformFloat("milkdrop_image_alpha", 1.0f);]=]
    [=[            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);]=]
    [=[            // Match the sampler state left by BeatDrop's preceding passes:
            // blur clamps; shader warp restores repeat; fixed warp uses wrap.
            const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
                (shaderWarp || *perFrameContext.wrap > 0.0001));
            m_textureSampler.WrapMode(repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
            m_textureSampler.Bind(0);]=]
    [=[const auto iterations = m_thickOutline ? 4 : 1;]=]
    [=[const auto iterations = static_cast<int>(*m_perFrameContext.thick) != 0 ? 4 : 1;]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/BlurTexture.hpp
    [=[    void SetRequiredBlurLevel(BlurLevel level);]=]
    [=[    void SetRequiredBlurLevel(BlurLevel level);
    bool IsRequired() const { return m_blurLevel != BlurLevel::None; }]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.hpp
    [=[    PerPixelMesh();]=]
    [=[    PerPixelMesh();
    bool HasWarpShader() const { return m_warpShader != nullptr; }]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[    void Draw();]=]
    [=[    void Draw(const PerFrameContext& perFrameContext, bool shaderWarp);]=]
    [=[class CustomShape]=] [=[class PerFrameContext;
class CustomShape]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[#include "PresetFileParser.hpp"]=]
    [=[#include "PresetFileParser.hpp"
#include "PerFrameContext.hpp"]=]
    [=[void CustomShape::Draw()]=]
    [=[void CustomShape::Draw(const PerFrameContext& perFrameContext, bool shaderWarp)]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[        shape->Draw();]=]
    [=[        shape->Draw(m_perFrameContext, m_perPixelMesh.HasWarpShader());]=]
)

# The old compositor was reading static INI values, silently ignoring equation
# assignments to echo_zoom, echo_alpha, echo_orient, gamma and filter toggles.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/VideoEcho.hpp
    [=[void Draw();]=] [=[void Draw(const PerFrameContext& perFrameContext);]=]
    [=[void DrawVideoEcho();]=] [=[void DrawVideoEcho(const PerFrameContext& perFrameContext);]=]
    [=[void DrawGammaAdjustment();]=] [=[void DrawGammaAdjustment(const PerFrameContext& perFrameContext);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/VideoEcho.cpp
    [=[void VideoEcho::Draw()]=] [=[void VideoEcho::Draw(const PerFrameContext& perFrameContext)]=]
    [=[void VideoEcho::DrawVideoEcho()]=] [=[void VideoEcho::DrawVideoEcho(const PerFrameContext& perFrameContext)]=]
    [=[void VideoEcho::DrawGammaAdjustment()]=] [=[void VideoEcho::DrawGammaAdjustment(const PerFrameContext& perFrameContext)]=]
    [=[DrawVideoEcho();]=] [=[DrawVideoEcho(perFrameContext);]=]
    [=[DrawGammaAdjustment();]=] [=[DrawGammaAdjustment(perFrameContext);]=]
    [=[m_presetState.videoEchoZoom]=] [=[static_cast<float>(*perFrameContext.echo_zoom)]=]
    [=[m_presetState.videoEchoAlpha]=] [=[static_cast<float>(*perFrameContext.echo_alpha)]=]
    [=[m_presetState.videoEchoOrientation % 4]=] [=[static_cast<int>(*perFrameContext.echo_orient) % 4]=]
    [=[m_presetState.gammaAdj]=] [=[static_cast<float>(*perFrameContext.gamma)]=]
    [=[        m_sampler.Bind(0);]=]
    [=[        m_sampler.WrapMode(*perFrameContext.wrap > 0.5 ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        m_sampler.Bind(0);]=]
    [=[            m_shade[i][k] = 0.5f + 0.5f * m_shade[i][k];]=]
    [=[            m_shade[i][k] = 0.5f + 0.5f * m_shade[i][k];
            const float amount = m_presetState.shader > 0.001f ? m_presetState.shader : 0.0f;
            m_shade[i][k] = m_shade[i][k] * amount + (1.0f - amount);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Filters.hpp
    [=[#include "PresetState.hpp"]=]
    [=[#include "PresetState.hpp"
#include "PerFrameContext.hpp"]=]
    [=[void Draw();]=] [=[void Draw(const PerFrameContext& perFrameContext);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Filters.cpp
    [=[void Filters::Draw()
{]=]
    [=[void Filters::Draw(const PerFrameContext& perFrameContext)
{
    if (*perFrameContext.brighten <= 0.5 && *perFrameContext.darken <= 0.5 &&
        *perFrameContext.solarize <= 0.5 && *perFrameContext.invert <= 0.5) return;]=]
    [=[if (m_presetState.brighten)]=] [=[if (*perFrameContext.brighten > 0.5)]=]
    [=[if (m_presetState.darken)]=] [=[if (*perFrameContext.darken > 0.5)]=]
    [=[if (m_presetState.solarize)]=] [=[if (*perFrameContext.solarize > 0.5)]=]
    [=[if (m_presetState.invert)]=] [=[if (*perFrameContext.invert > 0.5)]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/FinalComposite.cpp
    [=[        if (presetState.brighten ||
            presetState.darken ||
            presetState.solarize ||
            presetState.invert)
        {
            m_filters = std::make_unique<Filters>(presetState);
        }]=]
    [=[        m_filters = std::make_unique<Filters>(presetState);]=]
    [=[m_videoEcho->Draw();]=] [=[m_videoEcho->Draw(perFrameContext);]=]
    [=[m_filters->Draw();]=] [=[m_filters->Draw(perFrameContext);]=]
)

# MilkDrop exposes the inverse aspect in equation contexts, consistently with
# the per-frame variables. Keep the existing once-per-frame reset semantics.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelContext.cpp
    [=[*aspectx = static_cast<PRJM_EVAL_F>(state.renderContext.aspectX);]=]
    [=[*aspectx = static_cast<PRJM_EVAL_F>(state.renderContext.invAspectX);]=]
    [=[*aspecty = static_cast<PRJM_EVAL_F>(state.renderContext.aspectY);]=]
    [=[*aspecty = static_cast<PRJM_EVAL_F>(state.renderContext.invAspectY);]=]
)

foreach(WARP_SOURCE IN ITEMS PerPixelMesh MilkdropShader)
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/${WARP_SOURCE}.cpp
        "#include \"${WARP_SOURCE}.hpp\""
        "#include \"${WARP_SOURCE}.hpp\"\n#include \"WarpShaderCompatibility.hpp\""
    )
endforeach()
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.cpp
    [=[staticShaders->GetPresetWarpVertexShader(),]=]
    [=[milkdrop::CompatibleWarpVertexShader(staticShaders->GetPresetWarpVertexShader()),]=]
)

# MilkDrop's shader layer always binds sampler_main as wrap and sampler_fc_main as
# clamp; bTexWrap only controls the fixed-function warp. projectM reused one
# bTexWrap-driven sampler for the fixed warp and then rebound it on unit 0 after
# the preset shader's descriptors were bound, so every warp shader got a clamped
# sampler_main when bTexWrap=0. Presets which rely on wrap (e.g. Flexi - trickster
# interstate, whose UVs are polar coordinates) rendered fully black. Only bind the
# legacy sampler when there is no preset warp shader.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.cpp
    [=[    assert(!presetState.mainTexture.expired());
    presetState.mainTexture.lock()->Bind(0);

    // Set wrap mode and bind the sampler to get interpolation right.
    if (*perFrameContext.wrap > 0.0001f)
    {
        m_perPixelSampler.WrapMode(GL_REPEAT);
    }
    else
    {
        m_perPixelSampler.WrapMode(GL_CLAMP_TO_EDGE);
    }
    m_perPixelSampler.Bind(0);]=]
    [=[    assert(!presetState.mainTexture.expired());
    if (!m_warpShader)
    {
        // Fixed-function warp: bTexWrap selects the sampler addressing mode.
        presetState.mainTexture.lock()->Bind(0);
        m_perPixelSampler.WrapMode(*perFrameContext.wrap > 0.0001f ? GL_REPEAT : GL_CLAMP_TO_EDGE);
        m_perPixelSampler.Bind(0);
    }
    // With a preset warp shader, its own sampler descriptors select the mode
    // (sampler_main wraps, sampler_fc_main clamps). Do not override unit 0 with
    // the bTexWrap sampler here.]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[MilkdropStaticShaders::Get()->GetPresetWarpVertexShader(),]=]
    [=[milkdrop::CompatibleWarpVertexShader(MilkdropStaticShaders::Get()->GetPresetWarpVertexShader()),]=]
)
