# Keep all equation contexts and their shared state arrays at the same size.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Constants.hpp
    [=[static constexpr int QVarCount = 32;]=]
    [=[static constexpr int QVarCount = 64;]=]
)

# Uniform uploads iterate QVarCount. Add the matching shader uniform banks and aliases.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());]=]
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());
    for (int bank = 8; bank < QVarCount / 4; ++bank)
    {
        const std::string name = std::string("_q") + static_cast<char>('a' + bank);
        fullSource.append("\nuniform float4 " + name + ";\n");
        for (int component = 0; component < 4; ++component)
            fullSource.append("#define q" + std::to_string(bank * 4 + component + 1) +
                              " " + name + "." + "xyzw"[component] + "\n");
    }]=]
    [=[// set program uniform "_q[a-h]" values (_qa.x, _qa.y, _qa.z, _qa.w, _qb.x, _qb.y ... ) alias q[1-32]]=]
    [=[// set program uniform "_q[a-p]" values (_qa.x, _qa.y, _qa.z, _qa.w, _qb.x, _qb.y ... ) alias q[1-64]]=]
)
