# Extend build-directory copies; no changes to the vendored engine.
patch_projectm_copy(hlslparser vendor/hlslparser/src/HLSLParser.cpp
    [=[        INTRINSIC_FLOAT1_FUNCTION( "ceil" ),]=]
    [=[        INTRINSIC_FLOAT1_FUNCTION( "ceil" ),
        INTRINSIC_FLOAT1_FUNCTION( "degrees" ),
        INTRINSIC_FLOAT1_FUNCTION( "radians" ),
        INTRINSIC_FLOAT1_FUNCTION( "trunc" ),
        Intrinsic("determinant", HLSLBaseType_Float, HLSLBaseType_Float2x2),
        Intrinsic("determinant", HLSLBaseType_Float, HLSLBaseType_Float3x3),
        Intrinsic("determinant", HLSLBaseType_Float, HLSLBaseType_Float4x4),
        Intrinsic("isnan", HLSLBaseType_Bool2, HLSLBaseType_Float2),
        Intrinsic("isinf", HLSLBaseType_Bool2, HLSLBaseType_Float2),
        Intrinsic("isnan", HLSLBaseType_Bool3, HLSLBaseType_Float3),
        Intrinsic("isinf", HLSLBaseType_Bool3, HLSLBaseType_Float3),
        Intrinsic("isnan", HLSLBaseType_Bool4, HLSLBaseType_Float4),
        Intrinsic("isinf", HLSLBaseType_Bool4, HLSLBaseType_Float4),
        Intrinsic("tex3Dgrad", HLSLBaseType_Float4, HLSLBaseType_Sampler3D, HLSLBaseType_Float3, HLSLBaseType_Float3, HLSLBaseType_Float3),
        Intrinsic("texCUBEgrad", HLSLBaseType_Float4, HLSLBaseType_SamplerCube, HLSLBaseType_Float3, HLSLBaseType_Float3, HLSLBaseType_Float3),]=]
    [=[Intrinsic( "fwidth", HLSLBaseType_Float,  HLSLBaseType_Float2 )]=] [=[Intrinsic( "fwidth", HLSLBaseType_Float2,  HLSLBaseType_Float2 )]=]
    [=[Intrinsic( "fwidth", HLSLBaseType_Float,  HLSLBaseType_Float3 )]=] [=[Intrinsic( "fwidth", HLSLBaseType_Float3,  HLSLBaseType_Float3 )]=]
    [=[Intrinsic( "fwidth", HLSLBaseType_Float,  HLSLBaseType_Float4 )]=] [=[Intrinsic( "fwidth", HLSLBaseType_Float4,  HLSLBaseType_Float4 )]=]
 )
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[    // Remap intrinstic functions.]=]
    [=[    // Remap intrinstic functions.
    if (String_Equal(name, "round")) name = "roundEven";
    if (String_Equal(name, "tex3Dgrad") || String_Equal(name, "texCUBEgrad")) name = "textureGrad";
    if (String_Equal(name, "tex3Dlod")) name = m_tex3DlodFunction;
    if (String_Equal(name, "tex3Dbias")) name = "_milkdrop_tex3Dbias_glsl";]=]
    [=[    // Output the special function used to emulate tex3Dlod.]=]
    [=[    m_writer.WriteLine(0, "vec4 _milkdrop_tex3Dbias_glsl(sampler3D s, vec4 t) { return texture(s,t.xyz,t.w); }");
    // Output the special function used to emulate tex3Dlod.]=]
    [=[m_tex3DlodFunction, m_versionLegacy ? "texture3D" : "texture" )]=]
    [=[m_tex3DlodFunction, m_versionLegacy ? "texture3DLod" : "textureLod" )]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[#include "MilkdropShader.hpp"]=] [=[#include "MilkdropShader.hpp"
#include "UndocumentedShaderFunctions.hpp"]=]
    [=[    program = milkdrop::CompatibleShaderMath(program);]=]
    [=[    program = milkdrop::UndocumentedShaderFunctions(program);
    program = milkdrop::CompatibleShaderMath(program);]=]
)

# Strip complete sampler declarations, never function sampler parameters.
# Upstream (eae22de) hoisted the regex into a static `samplerDeclaration` and erases it through
# Utils::EraseRegexMatches(), which runs the regex with match_continuous on a sub-range starting at
# "sampler". That would make our leading \b always succeed, so use a plain regex_replace instead.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[samplerDeclaration("sampler(2D|3D|)(\\s+|\\().*")]=]
    [=[samplerDeclaration(R"(\bsampler(?:2D|3D|CUBE|1D)?[ \t]+[A-Za-z_]\w*(?:[ \t]*,[ \t]*[A-Za-z_]\w*)*[ \t]*;)")]=]
    [=[Utils::EraseRegexMatches(sourcePreprocessed, "sampler", samplerDeclaration);]=]
    [=[sourcePreprocessed = std::regex_replace(sourcePreprocessed, samplerDeclaration, "");]=]
)

# Plain texture names may also be declared with HLSL 1D/cube sampler syntax.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[sampler(?:2D|3D)?]=] [=[sampler(?:1D|2D|3D|CUBE)?]=]
)
