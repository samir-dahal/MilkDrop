# MilkDrop 1.05 beta equation and render state.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetState.hpp
    [=[    bool texWrap{true};]=]
    [=[    bool texWrap{true};
    float decay_r{0.0f};
    float decay_g{0.0f};
    float decay_b{0.0f};
    int x_wrap_mode{-1};
    int y_wrap_mode{-1};
    int x_echo_wrap{-1};
    int y_echo_wrap{-1};
    std::array<double, 8> waveValues{};
    std::array<double, 8> frameWaveValues{};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetState.cpp
    [=[    texWrap = parsedFile.GetBool("bTexWrap", parsedFile.GetBool("wrap", texWrap));]=]
    [=[    texWrap = parsedFile.GetBool("bTexWrap", parsedFile.GetBool("wrap", texWrap));
    decay_r = parsedFile.GetFloat("fDecayR", decay_r);
    decay_g = parsedFile.GetFloat("fDecayG", decay_g);
    decay_b = parsedFile.GetFloat("fDecayB", decay_b);
    x_wrap_mode = parsedFile.GetInt("nWrapMode_x", x_wrap_mode);
    y_wrap_mode = parsedFile.GetInt("nWrapMode_y", y_wrap_mode);
    x_echo_wrap = parsedFile.GetInt("nEchoWrap_x", x_echo_wrap);
    y_echo_wrap = parsedFile.GetInt("nEchoWrap_y", y_echo_wrap);]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerFrameContext.hpp
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};]=]
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};
    PRJM_EVAL_F* decay_r{};
    PRJM_EVAL_F* decay_g{};
    PRJM_EVAL_F* decay_b{};
    PRJM_EVAL_F* x_wrap_mode{};
    PRJM_EVAL_F* y_wrap_mode{};
    PRJM_EVAL_F* x_echo_wrap{};
    PRJM_EVAL_F* y_echo_wrap{};
    PRJM_EVAL_F* w_vars[8]{};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerFrameContext.cpp
    [=[    REG_VAR(time);]=]
    [=[    REG_VAR(time);
    REG_VAR(decay_r);
    REG_VAR(decay_g);
    REG_VAR(decay_b);
    REG_VAR(x_wrap_mode);
    REG_VAR(y_wrap_mode);
    REG_VAR(x_echo_wrap);
    REG_VAR(y_echo_wrap);
    for (int i = 0; i < 8; ++i)
    {
        const auto name = "w" + std::to_string(i + 1);
        w_vars[i] = projectm_eval_context_register_variable(perFrameCodeContext, name.c_str());
    }]=]
    [=[    *decay = static_cast<PRJM_EVAL_F>(state.decay);]=]
    [=[    *decay = static_cast<PRJM_EVAL_F>(state.decay);
    *decay_r = state.decay_r;
    *decay_g = state.decay_g;
    *decay_b = state.decay_b;
    *x_wrap_mode = state.x_wrap_mode;
    *y_wrap_mode = state.y_wrap_mode;
    *x_echo_wrap = state.x_echo_wrap;
    *y_echo_wrap = state.y_echo_wrap;
    for (int i = 0; i < 8; ++i) *w_vars[i] = state.waveValues[i];]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelContext.hpp
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};]=]
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};
    PRJM_EVAL_F* decay_r{};
    PRJM_EVAL_F* decay_g{};
    PRJM_EVAL_F* decay_b{};
    PRJM_EVAL_F* w_vars[8]{};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelContext.cpp
    [=[    REG_VAR(time);]=]
    [=[    REG_VAR(time);
    REG_VAR(decay_r);
    REG_VAR(decay_g);
    REG_VAR(decay_b);
    for (int i = 0; i < 8; ++i)
    {
        const auto name = "w" + std::to_string(i + 1);
        w_vars[i] = projectm_eval_context_register_variable(perPixelCodeContext, name.c_str());
    }]=]
    [=[    for (int q = 0; q < QVarCount; q++)
    {
        state.frameQVariables]=]
    [=[    for (int i = 0; i < 8; ++i)
    {
        state.frameWaveValues[i] = *perFrameState.w_vars[i];
        *w_vars[i] = *perFrameState.w_vars[i];
    }

    for (int q = 0; q < QVarCount; q++)
    {
        state.frameQVariables]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/ShapePerFrameContext.hpp
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};]=]
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};
    PRJM_EVAL_F* tex_capture{};
    PRJM_EVAL_F* tex_cx{};
    PRJM_EVAL_F* tex_cy{};
    PRJM_EVAL_F* x_wrap_mode{};
    PRJM_EVAL_F* y_wrap_mode{};
    PRJM_EVAL_F* w_vars[8]{};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/ShapePerFrameContext.cpp
    [=[    REG_VAR(time);]=]
    [=[    REG_VAR(time);
    REG_VAR(tex_capture);
    REG_VAR(tex_cx);
    REG_VAR(tex_cy);
    REG_VAR(x_wrap_mode);
    REG_VAR(y_wrap_mode);
    for (int i = 0; i < 8; ++i)
    {
        const auto name = "w" + std::to_string(i + 1);
        w_vars[i] = projectm_eval_context_register_variable(perFrameCodeContext, name.c_str());
    }]=]
    [=[    *tex_zoom = static_cast<double>(shape.m_tex_zoom);]=]
    [=[    *tex_zoom = static_cast<double>(shape.m_tex_zoom);
    *tex_capture = shape.m_tex_capture;
    *tex_cx = shape.m_tex_cx;
    *tex_cy = shape.m_tex_cy;
    *x_wrap_mode = shape.m_x_wrap_mode;
    *y_wrap_mode = shape.m_y_wrap_mode;
    for (int i = 0; i < 8; ++i) *w_vars[i] = state.frameWaveValues[i];]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformPerFrameContext.hpp
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};]=]
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};
    PRJM_EVAL_F* w_vars[8]{};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformPerFrameContext.cpp
    [=[    REG_VAR(time);]=]
    [=[    REG_VAR(time);
    for (int i = 0; i < 8; ++i)
    {
        const auto name = "w" + std::to_string(i + 1);
        w_vars[i] = projectm_eval_context_register_variable(perFrameCodeContext, name.c_str());
    }]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformPerPointContext.hpp
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};]=]
    [=[    PRJM_EVAL_F* q_vars[QVarCount]{};
    PRJM_EVAL_F* w_vars[8]{};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformPerPointContext.cpp
    [=[    REG_VAR(time);]=]
    [=[    REG_VAR(time);
    for (int i = 0; i < 8; ++i)
    {
        const auto name = "w" + std::to_string(i + 1);
        w_vars[i] = projectm_eval_context_register_variable(perPointCodeContext, name.c_str());
    }]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomWaveform.hpp
    [=[    void Draw(const PerFrameContext& presetPerFrameContext);]=]
    [=[    void Draw(const PerFrameContext& presetPerFrameContext);
    bool DrawsInBackground() const { return m_drawsInBackground; }]=]
    [=[    bool m_enabled{false};]=]
    [=[    bool m_drawsInBackground{false};
    bool m_enabled{false};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomWaveform.cpp
    [=[    m_enabled = parsedFile.GetBool(wavecodePrefix + "enabled", m_enabled);]=]
    [=[    m_enabled = parsedFile.GetBool(wavecodePrefix + "enabled", m_enabled);
    m_drawsInBackground = parsedFile.GetBool(wavecodePrefix + "bDrawBack", false);]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[    void Draw(const PerFrameContext& perFrameContext, bool shaderWarp);]=]
    [=[    void Draw(const PerFrameContext& perFrameContext, bool shaderWarp);
    bool DrawsInBackground() const { return m_drawsInBackground; }]=]
    [=[    bool m_enabled{false};]=]
    [=[    bool m_drawsInBackground{false};
    bool m_enabled{false};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[    m_enabled = parsedFile.GetBool(shapecodePrefix + "enabled", m_enabled);]=]
    [=[    m_enabled = parsedFile.GetBool(shapecodePrefix + "enabled", m_enabled);
    m_drawsInBackground = parsedFile.GetBool(shapecodePrefix + "bDrawBack", false);]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomWaveform.cpp
    [=[    m_perFrameContext.LoadStateVariables(m_presetState, presetPerFrameContext, *this);]=]
    [=[    m_perFrameContext.LoadStateVariables(m_presetState, presetPerFrameContext, *this);
    for (int i = 0; i < 8; ++i) *m_perFrameContext.w_vars[i] = *presetPerFrameContext.w_vars[i];]=]
    [=[    m_perPointContext.LoadReadOnlyStateVariables(presetPerFrameContext);]=]
    [=[    m_perPointContext.LoadReadOnlyStateVariables(presetPerFrameContext);
    for (int i = 0; i < 8; ++i) *m_perPointContext.w_vars[i] = *m_perFrameContext.w_vars[i];]=]
    [=[    if (alignEdgeLine)
    {]=]
    [=[    if (m_index == 0)
    {
        for (int i = 0; i < 8; ++i) m_presetState.waveValues[i] = *m_perPointContext.q_vars[i];
    }

    if (alignEdgeLine)
    {]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[    // y-flip the previous frame]=]
    [=[    for (auto& shape : m_customShapes)
    {
        if (shape->DrawsInBackground()) shape->Draw(m_perFrameContext, m_perPixelMesh.HasWarpShader());
    }
    for (auto& wave : m_customWaveforms)
    {
        if (wave->DrawsInBackground()) wave->Draw(m_perFrameContext);
    }

    // y-flip the previous frame]=]
    [=[        shape->Draw(m_perFrameContext, m_perPixelMesh.HasWarpShader());]=]
    [=[        if (!shape->DrawsInBackground()) shape->Draw(m_perFrameContext, m_perPixelMesh.HasWarpShader());]=]
    [=[        wave->Draw(m_perFrameContext);]=]
    [=[        if (!wave->DrawsInBackground()) wave->Draw(m_perFrameContext);]=]
)

patch_projectm_copy(Renderer src/libprojectM/Renderer/Sampler.hpp
    [=[    void WrapMode(GLint wrapMode);]=]
    [=[    void WrapMode(GLint wrapMode);
    void WrapAxes(GLint s, GLint t);]=]
)

patch_projectm_copy(Renderer src/libprojectM/Renderer/Sampler.cpp
    [=[auto Sampler::WrapMode() const -> GLint]=]
    [=[void Sampler::WrapAxes(GLint s, GLint t)
{
    WrapMode(s);
    if (s == t) return;
    glSamplerParameteri(m_samplerId, GL_TEXTURE_WRAP_T, t);
    // Force the ordinary single-mode setter to restore both axes next time.
    m_wrapMode = 0;
}

auto Sampler::WrapMode() const -> GLint]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.cpp
    [=[#include "PerPixelMesh.hpp"]=]
    [=[#include "BetaTextureAddress.hpp"
#include "PerPixelMesh.hpp"]=]
    [=[        m_perPixelSampler.WrapMode(*perFrameContext.wrap > 0.0001f ? GL_REPEAT : GL_CLAMP_TO_EDGE);]=]
    [=[        const GLint fallback = *perFrameContext.wrap > 0.0001f ? GL_REPEAT : GL_CLAMP_TO_EDGE;
        m_perPixelSampler.WrapAxes(milkdrop::BetaTextureAddress(*perFrameContext.x_wrap_mode, fallback),
                                   milkdrop::BetaTextureAddress(*perFrameContext.y_wrap_mode, fallback));]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/VideoEcho.cpp
    [=[#include "VideoEcho.hpp"]=]
    [=[#include "BetaTextureAddress.hpp"
#include "VideoEcho.hpp"]=]
    [=[        float const zoom = (pass == 0) ? 1.0f : videoEchoZoom;]=]
    [=[        float const zoom = (pass == 0) ? 1.0f : videoEchoZoom;
        const GLint fallback = *perFrameContext.wrap > 0.5 ? GL_REPEAT : GL_CLAMP_TO_EDGE;
        m_sampler.WrapAxes(milkdrop::BetaTextureAddress(pass == 1 ? *perFrameContext.x_echo_wrap : -1, fallback),
                           milkdrop::BetaTextureAddress(pass == 1 ? *perFrameContext.y_echo_wrap : -1, fallback));]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.hpp
    [=[    Renderer::VertexBuffer<Renderer::Point> m_stretchBuffer]=]
    [=[    Renderer::VertexBuffer<Renderer::Color> m_decayBuffer{Renderer::VertexBufferUsage::StreamDraw};
    Renderer::VertexBuffer<Renderer::Point> m_stretchBuffer]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelMesh.cpp
    [=[    m_stretchBuffer.InitializeAttributePointer(7);]=]
    [=[    m_stretchBuffer.InitializeAttributePointer(7);
    m_decayBuffer.Bind();
    m_decayBuffer.InitializeAttributePointer(8);
    Renderer::VertexBuffer<Renderer::Color>::SetEnableAttributeArray(8, true);]=]
    [=[        m_stretchBuffer.Resize(vertexCount);]=]
    [=[        m_stretchBuffer.Resize(vertexCount);
        m_decayBuffer.Resize(vertexCount);]=]
    [=[                perPixelContext.ExecutePerPixelCode();]=]
    [=[                *perPixelContext.decay_r = *perFrameContext.decay_r;
                *perPixelContext.decay_g = *perFrameContext.decay_g;
                *perPixelContext.decay_b = *perFrameContext.decay_b;

                perPixelContext.ExecutePerPixelCode();]=]
    [=[            vertex++;
        }
    }

    m_warpMesh.Update();]=]
    [=[            if (!m_warpShader)
            {
                const auto* channels = perPixelContext.perPixelCodeHandle ? &perPixelContext : nullptr;
                const double scalar = *perFrameContext.decay;
                const auto resolve = [scalar](double channel) {
                    return channel == 0.0 ? scalar : channel;
                };
                const auto packed = milkdrop::PackLegacyDecay(
                    resolve(channels ? *channels->decay_r : *perFrameContext.decay_r),
                    resolve(channels ? *channels->decay_g : *perFrameContext.decay_g),
                    resolve(channels ? *channels->decay_b : *perFrameContext.decay_b));
                constexpr float byteScale = 1.0f / 255.0f;
                m_decayBuffer[vertex] = Renderer::Color(
                    static_cast<float>((packed >> 16) & 255u) * byteScale,
                    static_cast<float>((packed >> 8) & 255u) * byteScale,
                    static_cast<float>(packed & 255u) * byteScale,
                    1.0f);
            }

            vertex++;
        }
    }

    m_warpMesh.Update();]=]
    [=[    m_stretchBuffer.Update();]=]
    [=[    m_stretchBuffer.Update();
    if (!m_warpShader) m_decayBuffer.Update();]=]
    [=[milkdrop::CompatibleWarpVertexShader(staticShaders->GetPresetWarpVertexShader()),]=]
    [=[milkdrop::CompatibleWarpVertexShader(staticShaders->GetPresetWarpVertexShader(), true),]=]
)

# Register a private evaluator intrinsic so presets can still use a variable named sound.
target_include_directories(projectM_eval PRIVATE "${PROJECTM_SOURCE_DIR}/vendor/projectm-eval/projectm-eval")
patch_projectm_source(projectM_eval vendor/projectm-eval/projectm-eval/TreeFunctions.c
    [=[static prjm_eval_function_def_t intrinsic_function_table[] = {]=]
    [=[extern PRJM_EVAL_F milkdrop_beta_sound(PRJM_EVAL_F first, PRJM_EVAL_F last);
static prjm_eval_function_decl(beta_sound);

static prjm_eval_function_def_t intrinsic_function_table[] = {]=]
    [=[static prjm_eval_function_def_t intrinsic_function_table[] = {]=]
    [=[static prjm_eval_function_def_t intrinsic_function_table[] = {
    { "__milkdrop_beta_sound", prjm_eval_func_beta_sound, 2, false, false },]=]
    [=[/* Math functions */
prjm_eval_function_decl(sin)]=]
    [=[static prjm_eval_function_decl(beta_sound)
{
    assert_valid_ctx();
    PRJM_EVAL_F first = 0.0, last = 0.0;
    PRJM_EVAL_F* value = &first;
    invoke_arg(0, &value);
    first = *value;
    value = &last;
    invoke_arg(1, &value);
    assign_ret_val(milkdrop_beta_sound(first, *value));
}

/* Math functions */
prjm_eval_function_decl(sin)]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetFileParser.cpp
    [=[#include <sstream>]=]
    [=[#include <regex>
#include <sstream>]=]
    [=[    return codeStr;]=]
    [=[    if (shaderCode) return codeStr;
    static const std::regex soundCall(R"((^|[^a-z0-9_.])sound(?=\s*\())", std::regex::icase);
    return std::regex_replace(codeStr, soundCall, "$1__milkdrop_beta_sound");]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[#include "MilkdropPreset.hpp"]=]
    [=[#include "BetaSound.hpp"
#include <Renderer/BlendMode.hpp>
#include "MilkdropPreset.hpp"]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
    [=[#include "PresetState.hpp"]=]
    [=[#include <Renderer/CopyTexture.hpp>
#include "PresetState.hpp"]=]
    [=[    bool DrawsInBackground() const { return m_drawsInBackground; }]=]
    [=[    bool DrawsInBackground() const { return m_drawsInBackground; }
    void CaptureFeedback(Renderer::ShaderCache& shaderCache, const std::shared_ptr<Renderer::Texture>& feedback);]=]
    [=[    bool m_drawsInBackground{false};]=]
    [=[    float m_tex_cx{0.5f};
    float m_tex_cy{0.5f};
    bool m_tex_capture{true};
    bool m_betaFeedback{false};
    int m_x_wrap_mode{-1};
    int m_y_wrap_mode{-1};
    std::unique_ptr<Renderer::CopyTexture> m_feedbackCapture;
    bool m_captureUsed{false};
    bool m_captureUpdate{false};
    glm::vec2 m_captureCenter{0.5f, 0.5f};
    bool m_drawsInBackground{false};]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
    [=[#include "CustomShape.hpp"]=]
    [=[#include "BetaTextureAddress.hpp"
#include "CustomShape.hpp"]=]
    [=[    m_tex_zoom = parsedFile.GetFloat(shapecodePrefix + "tex_zoom", m_tex_zoom);]=]
    [=[    m_tex_zoom = parsedFile.GetFloat(shapecodePrefix + "tex_zoom", m_tex_zoom);
    m_tex_cx = parsedFile.GetFloat(shapecodePrefix + "tex_cx", m_tex_cx);
    m_tex_cy = parsedFile.GetFloat(shapecodePrefix + "tex_cy", m_tex_cy);
    m_tex_capture = parsedFile.GetBool(shapecodePrefix + "tex_capture", m_tex_capture);
    m_betaFeedback = !parsedFile.GetString(shapecodePrefix + "tex_capture", "").empty();
    m_x_wrap_mode = parsedFile.GetInt(shapecodePrefix + "x_wrap_mode", m_x_wrap_mode);
    m_y_wrap_mode = parsedFile.GetInt(shapecodePrefix + "y_wrap_mode", m_y_wrap_mode);]=]
    [=[    bool batchAdditive = false, batchTextured = false;]=]
    [=[    bool batchAdditive = false, batchTextured = false;
    GLint batchWrapX = GL_CLAMP_TO_EDGE, batchWrapY = GL_CLAMP_TO_EDGE;
    const bool useFeedback = m_image.empty() || (m_drawsInBackground &&
        m_presetState.renderContext.textureManager->GetTexture(m_image).Empty());
    const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
        (shaderWarp || *perFrameContext.wrap > 0.0001));
    const GLint fallbackX = !m_image.empty() || shaderWarp || m_presetState.blurTexture.IsRequired()
        ? (repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE)
        : milkdrop::BetaTextureAddress(*perFrameContext.x_wrap_mode, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);
    const GLint fallbackY = !m_image.empty() || shaderWarp || m_presetState.blurTexture.IsRequired()
        ? (repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE)
        : milkdrop::BetaTextureAddress(*perFrameContext.y_wrap_mode, repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);]=]
    [=[            // Match the sampler state left by BeatDrop's preceding passes:
            // blur clamps; shader warp restores repeat; fixed warp uses wrap.
            const bool repeat = !m_image.empty() || (!m_presetState.blurTexture.IsRequired() &&
                (shaderWarp || *perFrameContext.wrap > 0.0001));
            m_textureSampler.WrapMode(repeat ? GL_REPEAT : GL_CLAMP_TO_EDGE);]=]
    [=[            m_textureSampler.WrapAxes(batchWrapX, batchWrapY);]=]
    [=[                m_presetState.mainTexture.lock()->Bind(0);]=]
    [=[                const auto feedback = m_feedbackCapture ? m_feedbackCapture->Texture()
                    : m_presetState.mainTexture.lock();
                feedback->Bind(0);]=]
    [=[        const bool textured = static_cast<int>(*m_perFrameContext.textured) != 0;]=]
    [=[        const bool textured = static_cast<int>(*m_perFrameContext.textured) != 0;
        const auto wrapX = milkdrop::BetaTextureAddress(*m_perFrameContext.x_wrap_mode, fallbackX);
        const auto wrapY = milkdrop::BetaTextureAddress(*m_perFrameContext.y_wrap_mode, fallbackY);
        if (textured && useFeedback)
        {
            const glm::vec2 center{static_cast<float>(*m_perFrameContext.tex_cx),
                                   static_cast<float>(*m_perFrameContext.tex_cy)};
            const bool live = *m_perFrameContext.tex_capture != 0.0;
            if (m_betaFeedback || m_drawsInBackground || m_feedbackCapture || !live || center != glm::vec2(0.5f))
            {
                m_captureUsed = true;
                m_captureUpdate |= live;
                m_captureCenter = center;
            }
            if (m_drawsInBackground && !m_feedbackCapture) continue;
        }]=]
    [=[        if (additive != batchAdditive || textured != batchTextured ||]=]
    [=[        if (additive != batchAdditive || textured != batchTextured ||
            (textured && (wrapX != batchWrapX || wrapY != batchWrapY)) ||]=]
    [=[        batchAdditive = additive; batchTextured = textured;]=]
    [=[        batchAdditive = additive; batchTextured = textured;
        batchWrapX = wrapX; batchWrapY = wrapY;]=]
    [=[} // namespace MilkdropPreset]=]
    [=[void CustomShape::CaptureFeedback(Renderer::ShaderCache& shaderCache,
                                  const std::shared_ptr<Renderer::Texture>& feedback)
{
    if (!m_captureUsed)
    {
        m_feedbackCapture.reset();
        return;
    }

    if (!m_feedbackCapture || m_captureUpdate)
    {
        if (!m_feedbackCapture) m_feedbackCapture = std::make_unique<Renderer::CopyTexture>();
        m_feedbackCapture->Capture(shaderCache, feedback, m_captureCenter);
    }
    m_captureUsed = false;
    m_captureUpdate = false;
}

} // namespace MilkdropPreset]=]
)

patch_projectm_copy(Renderer src/libprojectM/Renderer/CopyTexture.hpp
    [=[    CopyTexture();]=]
    [=[    CopyTexture();
    void Capture(ShaderCache& shaderCache, const std::shared_ptr<Texture>& source, glm::vec2 center);]=]
    [=[              bool flipVertical, bool flipHorizontal);]=]
    [=[              bool flipVertical, bool flipHorizontal, glm::vec2 offset = {});]=]
)

patch_projectm_copy(Renderer src/libprojectM/Renderer/CopyTexture.cpp
    [=[uniform sampler2D texture_sampler;]=]
    [=[uniform sampler2D texture_sampler;
uniform vec2 capture_offset;]=]
    [=[    color = texture(texture_sampler, fragment_tex_coord);]=]
    [=[    color = texture(texture_sampler, fragment_tex_coord + capture_offset);]=]
    [=[                       bool flipVertical, bool flipHorizontal)
{
    glm::mat4x4 flipMatrix(1.0);]=]
    [=[                       bool flipVertical, bool flipHorizontal, glm::vec2 offset)
{
    glm::mat4x4 flipMatrix(1.0);]=]
    [=[    shader->SetUniformMat4x4("vertex_transformation", flipMatrix);]=]
    [=[    shader->SetUniformMat4x4("vertex_transformation", flipMatrix);
    shader->SetUniformFloat2("capture_offset", offset);]=]
    [=[    shader->SetUniformMat4x4("vertex_transformation", translationMatrix);]=]
    [=[    shader->SetUniformMat4x4("vertex_transformation", translationMatrix);
    shader->SetUniformFloat2("capture_offset", {});]=]
    [=[auto CopyTexture::Texture() -> std::shared_ptr<class Texture>]=]
    [=[void CopyTexture::Capture(ShaderCache& shaderCache, const std::shared_ptr<class Texture>& source, glm::vec2 center)
{
    if (!source || source->Empty()) return;
    UpdateTextureSize(source->Width(), source->Height());
    m_framebuffer.Bind(0);
    source->Bind(0);
    m_sampler.WrapMode(GL_REPEAT);
    m_sampler.FilterMode(GL_LINEAR);
    Copy(shaderCache, true, false, {center.x - 0.5f, 0.5f - center.y});
    Framebuffer::Unbind();
}

auto CopyTexture::Texture() -> std::shared_ptr<class Texture>]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[    // y-flip the image for final compositing again]=]
    [=[    Renderer::BlendMode::SetBlendActive(false);
    for (auto& shape : m_customShapes)
    {
        shape->CaptureFeedback(*renderContext.shaderCache, m_framebuffer.GetColorAttachmentTexture(m_currentFrameBuffer, 0));
    }

    // y-flip the image for final compositing again]=]
)

# Pass the full PCM buffers to sound() without changing FrameAudioData's layout.
add_configure_dependency("${CMAKE_CURRENT_LIST_DIR}/BetaSound.hpp")
target_include_directories(Audio PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
patch_projectm_copy(Audio src/libprojectM/Audio/PCM.cpp
    [=[#include "Audio/PCM.hpp"]=]
    [=[#include "Audio/PCM.hpp"
#include "BetaSound.hpp"]=]
    [=[    FrameAudioData data{};]=]
    [=[    FrameAudioData data{};
    milkdrop::BetaSoundScope::Prepare(m_waveformL, m_waveformR);]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[#include "BetaSound.hpp"]=]
    [=[#include "BetaSound.hpp"
#include <projectm-eval.h>

// The evaluator's private intrinsic calls this through its C ABI.
extern "C" PRJM_EVAL_F milkdrop_beta_sound(PRJM_EVAL_F first, PRJM_EVAL_F last)
{
    return static_cast<PRJM_EVAL_F>(milkdrop::BetaSoundScope::Sum(first, last));
}]=]
)


# Renderer consumers include these headers with a Renderer/ prefix.
foreach(HEADER IN ITEMS Sampler.hpp CopyTexture.hpp)
    projectm_patched_path(Renderer ${PROJECTM_SOURCE_DIR}/src/libprojectM/Renderer/${HEADER} BETA_HEADER)
    get_filename_component(BETA_RENDERER_ROOT ${BETA_HEADER} DIRECTORY)
    file(COPY_FILE "${BETA_HEADER}" "${BETA_RENDERER_ROOT}/Renderer/${HEADER}" ONLY_IF_DIFFERENT)
endforeach()
# The newer renderer can match the capture to the feedback color format.
if(EXISTS "${PROJECTM_SOURCE_DIR}/src/libprojectM/Renderer/ColorFormat.hpp")
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.hpp
        [=[const std::shared_ptr<Renderer::Texture>& feedback);]=]
        [=[const std::shared_ptr<Renderer::Texture>& feedback, const Renderer::ColorFormat& format);]=]
    )
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomShape.cpp
        [=[const std::shared_ptr<Renderer::Texture>& feedback)]=]
        [=[const std::shared_ptr<Renderer::Texture>& feedback, const Renderer::ColorFormat& format)]=]
        [=[        m_feedbackCapture->Capture(shaderCache, feedback, m_captureCenter);]=]
        [=[        m_feedbackCapture->SetColorFormat(format);
        m_feedbackCapture->Capture(shaderCache, feedback, m_captureCenter);]=]
    )
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
        [=[shape->CaptureFeedback(*renderContext.shaderCache, m_framebuffer.GetColorAttachmentTexture(m_currentFrameBuffer, 0));]=]
        [=[shape->CaptureFeedback(*renderContext.shaderCache, m_framebuffer.GetColorAttachmentTexture(m_currentFrameBuffer, 0),
                               Renderer::RenderTargetColorFormat(renderContext.targetColorBits));]=]
    )
endif()

patch_projectm_copy(projectM_main src/libprojectM/ProjectM.cpp
    [=[#include "ProjectM.hpp"]=]
    [=[#include "ProjectM.hpp"
#include "BetaSound.hpp"]=]
    [=[    auto audioData = m_audioStorage.GetFrameAudioData();]=]
    [=[    auto audioData = m_audioStorage.GetFrameAudioData();
    milkdrop::BetaSoundScope betaSound;]=]
)

