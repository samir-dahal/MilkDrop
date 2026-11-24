# Declaration cleanup must never match words inside comments. In particular,
# "// apply the main sampler" used to consume the following texture lookup,
# because the declaration regex permits a newline after the word sampler.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[    // Remove previous shader declarations]=]
    [=[    sourcePreprocessed = Utils::StripComments(sourcePreprocessed);

    // Remove previous shader declarations]=]
)

# D3D9 places pixel centers at integer coordinates; GLES uses half integers.
# Translate custom waves by half a framebuffer pixel so a wave at x=0 lands
# on the first column rather than outside its line rasterization coverage.
# Keep points on the last scanline inside the viewport too. In the legacy
# clamped warp, flat lines placed at or within 0.1% of an edge seed the outer
# feedback row. Align those lines with that row at every render resolution.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomWaveform.cpp
    [=[    std::vector<Renderer::Color> colors(sampleCount);]=]
    [=[    std::vector<Renderer::Color> colors(sampleCount);
    const double halfPixelY = 1.0 / m_presetState.renderContext.viewportSizeY;
    bool alignEdgeLine = !m_useDots && m_presetState.warpShader.empty() && *presetPerFrameContext.wrap <= 0.0001;
    double firstY = 0.0;]=]
    [=[        points[sample] = Renderer::Point(]=]
    [=[        const double y = (1.0 - 2.0 * *m_perPointContext.y) * m_presetState.renderContext.invAspectY;
        if (alignEdgeLine)
        {
            if (sample == 0)
            {
                firstY = y;
                alignEdgeLine = std::abs(y) >= 0.998 && std::abs(y) <= 1.0;
            }
            else
            {
                alignEdgeLine = y == firstY;
            }
        }
        const double shiftedY = y - halfPixelY;
        const double rasterY = y >= -1.0 && y <= 1.0 ? std::max(shiftedY, -1.0 + halfPixelY) : shiftedY;
        points[sample] = Renderer::Point(]=]
    [=[* m_presetState.renderContext.invAspectX),]=]
    [=[* m_presetState.renderContext.invAspectX + 1.0 / m_presetState.renderContext.viewportSizeX),]=]
    [=[static_cast<float>((*m_perPointContext.y * -2.0 + 1.0) * m_presetState.renderContext.invAspectY));]=]
    [=[static_cast<float>(rasterY));]=]
    [=[    if (m_useDots)
    {
        for (int sample = 0; sample < sampleCount; ++sample)]=]
    [=[    if (alignEdgeLine)
    {
        const auto edgeRow = static_cast<float>(std::copysign(1.0 - halfPixelY, firstY));
        for (auto& point : points)
        {
            point.SetY(edgeRow);
        }
    }

    if (m_useDots)
    {
        for (int sample = 0; sample < sampleCount; ++sample)]=]
)

# HLSL promotes the two branches of a conditional before assigning its result.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[            conditionalExpression->expressionType  = expression1->expressionType;]=]
    [=[            conditionalExpression->expressionType = expression1->expressionType;
            HLSLType promoted;
            if (GetBinaryOpResultType(HLSLBinaryOp_Add, expression1->expressionType,
                                      expression2->expressionType, promoted))
            {
                conditionalExpression->expressionType = promoted;
            }]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[OutputExpression( conditionalExpression->trueExpression, dstType );]=]
    [=[OutputExpression( conditionalExpression->trueExpression, &conditionalExpression->expressionType );]=]
    [=[OutputExpression( conditionalExpression->falseExpression, dstType );]=]
    [=[OutputExpression( conditionalExpression->falseExpression, &conditionalExpression->expressionType );]=]
)

# BeatDrop resolves random slots separately for each compiled shader. Preserve
# slot and prefix aliases within that stage, without coupling warp to composite.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[randomSlot <= 15]=] [=[randomSlot <= 99]=]
    [=[    std::locale loc;]=]
    [=[    std::locale loc;
    std::map<int, Renderer::TextureSamplerDescriptor> randomTextureDescriptors;
    std::map<std::string, Renderer::TextureSamplerDescriptor> prefixedRandomTextures;]=]
    [=[presetState.randomTextureDescriptors]=] [=[randomTextureDescriptors]=]
    [=[presetState.prefixedRandomTextures]=] [=[prefixedRandomTextures]=]
)

# The reference packs border colors through D3DCOLOR_RGBA, truncating and
# wrapping each component to a byte. GLES otherwise clamps negative RGB to black.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Border.cpp
    [=[#include <Renderer/BlendMode.hpp>]=]
    [=[#include <Renderer/BlendMode.hpp>
#include <cmath>]=]
    [=[            glVertexAttrib4f(1, r, g, b, a);]=]
    [=[            const auto packedChannel = [](float value) {
                if (!std::isfinite(value)) return 0.0f;
                const float scaled = value * 255.0f;
                if (!std::isfinite(scaled)) return 0.0f;
                float byte = static_cast<float>(std::fmod(std::trunc(static_cast<double>(scaled)), 256.0));
                if (byte < 0.0f) byte += 256.0f;
                return byte / 255.0f;
            };
            glVertexAttrib4f(1, packedChannel(r), packedChannel(g), packedChannel(b), packedChannel(a));]=]
)

# Echo/gamma vertex colors use the same packed-byte behavior as borders in D3D.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/VideoEcho.cpp
    [=[#include <Renderer/BlendMode.hpp>]=]
    [=[#include <Renderer/BlendMode.hpp>
#include <cmath>]=]
    [=[namespace MilkdropPreset {]=]
    [=[namespace MilkdropPreset {
static float PackedEchoChannel(float value)
{
    const float scaled = value * 255.0f;
    if (!std::isfinite(scaled)) return 0.0f;
    float byte = static_cast<float>(std::fmod(std::trunc(static_cast<double>(scaled)), 256.0));
    if (byte < 0.0f) byte += 256.0f;
    return byte / 255.0f;
}]=]
    [=[gamma * mix * m_shade[vertex][0],]=] [=[PackedEchoChannel(gamma * mix * m_shade[vertex][0]),]=]
    [=[gamma * mix * m_shade[vertex][1],]=] [=[PackedEchoChannel(gamma * mix * m_shade[vertex][1]),]=]
    [=[gamma * mix * m_shade[vertex][2],]=] [=[PackedEchoChannel(gamma * mix * m_shade[vertex][2]),]=]
    [=[mix * m_shade[vertex][0],]=] [=[PackedEchoChannel(mix * m_shade[vertex][0]),]=]
    [=[mix * m_shade[vertex][1],]=] [=[PackedEchoChannel(mix * m_shade[vertex][1]),]=]
    [=[mix * m_shade[vertex][2],]=] [=[PackedEchoChannel(mix * m_shade[vertex][2]),]=]
    [=[gamma * m_shade[vertex][0],]=] [=[PackedEchoChannel(gamma * m_shade[vertex][0]),]=]
    [=[gamma * m_shade[vertex][1],]=] [=[PackedEchoChannel(gamma * m_shade[vertex][1]),]=]
    [=[gamma * m_shade[vertex][2],]=] [=[PackedEchoChannel(gamma * m_shade[vertex][2]),]=]
)

# Keep legacy math intrinsics explicit on GLES feedback buffers.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[#include "MilkdropShader.hpp"]=]
    [=[#include "MilkdropShader.hpp"
#include "ShaderMathCompatibility.hpp"]=]
    [=[    // Preprocess define macros]=]
    [=[    program = milkdrop::CompatibleShaderMath(program);
    // Preprocess define macros]=]
)

# BeatDrop's BlurPasses binds m_lpVS[0], the previous feedback image,
# despite its comment describing the current post-warp frame.


# D3D distinguishes float1 (a one-component vector) from float (a scalar).
# pow(float3, float1) truncates to float1; pow(float3, float) splats the scalar.
# Losing this distinction turns Digital Sea's shared white detail into RGB bands.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLTree.h
    [=[        baseType    = _baseType;]=]
    [=[        baseType    = _baseType;
        oneComponentVector = false;]=]
    [=[    HLSLBaseType        baseType;]=]
    [=[    bool               oneComponentVector;
    HLSLBaseType        baseType;]=]
)
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[        type.baseType = HLSLBaseType_Float;
        break;
    case HLSLToken_Float2:]=]
    [=[        type.baseType = HLSLBaseType_Float;
        type.oneComponentVector = token == HLSLToken_Float1 || token == HLSLToken_Half1 || token == HLSLToken_Double1;
        break;
    case HLSLToken_Float2:]=]
    [=[    result.typeName     = NULL;]=]
    [=[    result.oneComponentVector = IsScalarType(result.baseType) &&
        (type1.oneComponentVector || type2.oneComponentVector);
    result.typeName     = NULL;]=]
    [=[        const HLSLFunction* function = &_intrinsic[i].function;
        if (String_Equal(function->name, name))]=]
    [=[        const HLSLFunction* function = &_intrinsic[i].function;
        if (String_Equal(name, "pow") && functionCall->numArguments == 2 &&
            (functionCall->argument->expressionType.oneComponentVector ||
             functionCall->argument->nextExpression->expressionType.oneComponentVector) &&
            !IsScalarType(function->returnType.baseType)) continue;
        if (String_Equal(function->name, name))]=]
)

# Legacy Direct3D expands small integer powers into multiplies and takes the
# reciprocal for negative exponents. A helper evaluates the base once.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[    if (m_tree->NeedsFunction("asin"))]=]
    [=[    if (m_tree->NeedsFunction("pow"))
    {
        for (const char* type : {"float", "vec2", "vec3", "vec4"})
        {
            m_writer.WriteLine(0, "%s _milkdrop_integer_pow(%s x, int exponent) {", type, type);
            m_writer.WriteLine(1, "%s value = %s(1.0);", type, type);
            m_writer.WriteLine(1, "for (int n = abs(exponent); n > 0; n /= 2) {");
            m_writer.WriteLine(2, "if (n %% 2 != 0) value *= x;");
            m_writer.WriteLine(2, "x *= x;");
            m_writer.WriteLine(1, "}");
            m_writer.WriteLine(1, "return exponent < 0 ? 1.0 / value : value;");
            m_writer.WriteLine(0, "}");
        }
    }

    if (m_tree->NeedsFunction("asin"))]=]
    [=[            if (argument[1]->nodeType == HLSLNodeType_LiteralExpression)
            {
                HLSLLiteralExpression* literalExpression = static_cast<HLSLLiteralExpression*>(argument[1]);]=]
    [=[            auto* exponent = argument[1];
            float exponentSign = 1.0f;
            if (exponent->nodeType == HLSLNodeType_UnaryExpression)
            {
                auto* unary = static_cast<HLSLUnaryExpression*>(exponent);
                if (unary->unaryOp == HLSLUnaryOp_Negative || unary->unaryOp == HLSLUnaryOp_Positive)
                {
                    exponentSign = unary->unaryOp == HLSLUnaryOp_Negative ? -1.0f : 1.0f;
                    exponent = unary->expression;
                }
            }
            if (exponent->nodeType == HLSLNodeType_LiteralExpression)
            {
                HLSLLiteralExpression* literalExpression = static_cast<HLSLLiteralExpression*>(exponent);]=]
    [=[                // Replace the function call with just arg 1.]=]
    [=[                value *= exponentSign;
                // This contiguous range was verified with ps_2_0 and ps_3_0.
                if (found && value >= -14.0f && value <= 16.0f &&
                    value == static_cast<int>(value) && value != 1.0f)
                {
                    m_writer.Write("_milkdrop_integer_pow(");
                    OutputExpression(argument[0], &functionCall->function->returnType);
                    m_writer.Write(", %d)", static_cast<int>(value));
                    handled = true;
                }

                // Replace the function call with just arg 1.]=]
)

# Disk textures need real mip levels and mip filtering for tex2Dlod focus blur.
# Keep feedback/blur buffers on their existing single-level samplers.
patch_projectm_copy(Renderer src/libprojectM/Renderer/Texture.hpp
    [=[    auto IsUserTexture() const -> bool;]=]
    [=[    auto IsUserTexture() const -> bool;
    auto HasMipmaps() const -> bool { return m_hasMipmaps; }]=]
    [=[    bool m_isUserTexture]=]
    [=[    mutable bool m_hasMipmaps{false};
    bool m_isUserTexture]=]
)
patch_projectm_copy(Renderer src/libprojectM/Renderer/Texture.cpp
    [=[    glBindTexture(m_target, 0);]=]
    [=[    if (m_isUserTexture && m_owned && m_target == GL_TEXTURE_2D && m_width > 0 && m_height > 0)
    {
        glGenerateMipmap(m_target);
        m_hasMipmaps = true;
    }
    glBindTexture(m_target, 0);]=]
)
patch_projectm_copy(Renderer src/libprojectM/Renderer/Sampler.hpp
    [=[    void FilterMode(GLint filterMode);]=]
    [=[    void FilterMode(GLint filterMode);
    void EnableMipmaps() {
        glSamplerParameteri(m_samplerId, GL_TEXTURE_MIN_FILTER,
            m_filterMode == GL_NEAREST ? GL_NEAREST_MIPMAP_NEAREST : GL_LINEAR_MIPMAP_LINEAR);
    }]=]
)
patch_projectm_copy(Renderer src/libprojectM/Renderer/TextureSamplerDescriptor.hpp
    [=[    std::shared_ptr<class Sampler> m_sampler;]=]
    [=[    mutable Sampler::Ptr m_mipmapSampler;
    std::shared_ptr<class Sampler> m_sampler;]=]
)
patch_projectm_copy(Renderer src/libprojectM/Renderer/TextureSamplerDescriptor.cpp
    [=[        m_texture->Bind(unit, m_sampler);]=]
    [=[        if (m_texture->HasMipmaps())
        {
            if (!m_mipmapSampler) {
                m_mipmapSampler = std::make_shared<libprojectM::Renderer::Sampler>(m_sampler->WrapMode(), m_sampler->FilterMode());
                m_mipmapSampler->EnableMipmaps();
            }
            m_texture->Bind(unit, m_mipmapSampler);
        }
        else m_texture->Bind(unit, m_sampler);]=]
)

# Renderer sources use local includes; other targets use Renderer/xxx includes.
# Publish the same patched layouts through both paths.
foreach(HEADER IN ITEMS Texture.hpp Sampler.hpp TextureSamplerDescriptor.hpp)
    projectm_patched_path(Renderer ${PROJECTM_SOURCE_DIR}/src/libprojectM/Renderer/${HEADER} MIP_HEADER)
    get_filename_component(MIP_ROOT ${MIP_HEADER} DIRECTORY)
    file(MAKE_DIRECTORY ${MIP_ROOT}/Renderer)
    configure_file(${MIP_HEADER} ${MIP_ROOT}/Renderer/${HEADER} COPYONLY)
endforeach()

# Presets share the pre-composite feedback image in BeatDrop, not the screen
# output. Normalization shaders amplify a bright imported composite indefinitely.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.hpp
    [=[    auto OutputTexture() const -> std::shared_ptr<Renderer::Texture> override;]=]
    [=[    auto OutputTexture() const -> std::shared_ptr<Renderer::Texture> override;
    auto FeedbackTexture() const -> std::shared_ptr<Renderer::Texture> {
        return m_framebuffer.GetColorAttachmentTexture(m_previousFrameBuffer, 0);
    }]=]
)
patch_projectm_copy(projectM_main src/libprojectM/ProjectM.cpp
    [=[#include "ProjectM.hpp"]=]
    [=[#include "ProjectM.hpp"
namespace libprojectM { namespace Renderer { class Texture; } namespace MilkdropPreset {
std::shared_ptr<Renderer::Texture> FeedbackImage(Preset* preset);
} }]=]
    [=[        preset->DrawInitialImage(m_activePreset->OutputTexture(), GetRenderContext());]=]
    [=[        preset->DrawInitialImage(MilkdropPreset::FeedbackImage(m_activePreset.get()), GetRenderContext());]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropPreset.cpp
    [=[namespace MilkdropPreset {]=]
    [=[namespace MilkdropPreset {
std::shared_ptr<Renderer::Texture> FeedbackImage(Preset* preset) {
    auto* milkdrop = dynamic_cast<MilkdropPreset*>(preset);
    return milkdrop ? milkdrop->FeedbackTexture() : preset->OutputTexture();
}]=]
)

include(${CMAKE_CURRENT_LIST_DIR}/UndocumentedShaderFunctions.cmake)

# Legacy textured drawing uses D3DTSS_ALPHAOP=SELECTARG1 / D3DTA_DIFFUSE.
# Multiplying by feedback alpha suppresses textured shapes as that alpha fades.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetState.cpp
    [=[#include "PresetState.hpp"]=]
    [=[#include "PresetState.hpp"
#include "LegacyTextureShader.hpp"]=]
    [=[staticShaders->GetTexturedDrawFragmentShader());]=]
    [=[milkdrop::LegacyTextureShader(staticShaders->GetTexturedDrawFragmentShader()));]=]
)

include(${CMAKE_CURRENT_LIST_DIR}/QVariables.cmake)
