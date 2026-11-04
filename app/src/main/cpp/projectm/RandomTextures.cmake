# Preserve the selected image across shader stages, but use each reference's
# uniform name and filtering/wrapping settings when binding that image.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[using libprojectM::MilkdropPreset::MilkdropStaticShaders;]=]
    [=[using libprojectM::MilkdropPreset::MilkdropStaticShaders;

// Descriptors can share a short random alias. Deduplicate individual uniforms,
// rather than the whole multi-line declaration returned by each descriptor.
static void AddShaderDeclarations(std::set<std::string>& declarations, const std::string& source)
{
    size_t start = 0;
    while (start < source.size())
    {
        const auto end = source.find('\n', start);
        const auto count = end == std::string::npos ? source.size() - start : end - start;
        if (count > 0) declarations.insert(source.substr(start, count) + "\n");
        if (end == std::string::npos) break;
        start = end + 1;
    }
}]=]
    [=[samplerDeclarations.insert(desc.SamplerDeclaration());]=]
    [=[AddShaderDeclarations(samplerDeclarations, desc.SamplerDeclaration());]=]
    [=[texSizeDeclarations.insert(desc.TexSizeDeclaration());]=]
    [=[AddShaderDeclarations(texSizeDeclarations, desc.TexSizeDeclaration());]=]
    [=[m_textureSamplerDescriptors.push_back(presetState.randomTextureDescriptors.at(randomSlot));]=]
    [=[const auto& selected = presetState.randomTextureDescriptors.at(randomSlot);
                    m_textureSamplerDescriptors.emplace_back(selected.Texture(),
                        presetState.renderContext.textureManager->GetSampler(name), name, baseName);]=]
)

# Strip filtering/wrapping qualifiers before applying the random filename prefix.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetState.hpp
    [=[    std::map<int, Renderer::TextureSamplerDescriptor> randomTextureDescriptors;]=]
    [=[    std::map<std::string, Renderer::TextureSamplerDescriptor> prefixedRandomTextures;
    std::map<int, Renderer::TextureSamplerDescriptor> randomTextureDescriptors;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[            if (randomSlot >= 0 && randomSlot <= 15)
            {]=]
    [=[            if (randomSlot >= 0 && randomSlot <= 15)
            {
                // Two filename prefixes can deliberately share rand00. Cache
                // each full unqualified name, while retaining the short slot
                // alias for presets that reference rand00 without a prefix.
                if (lowerCaseName.size() > 7 && lowerCaseName[6] == '_')
                {
                    auto found = presetState.prefixedRandomTextures.find(lowerCaseName);
                    if (found == presetState.prefixedRandomTextures.end())
                    {
                        auto selected = presetState.renderContext.textureManager->GetRandomTexture(name);
                        found = presetState.prefixedRandomTextures.emplace(lowerCaseName, selected).first;
                        presetState.randomTextureDescriptors.emplace(randomSlot, selected);
                    }
                    const auto& selected = found->second;
                    m_textureSamplerDescriptors.emplace_back(selected.Texture(),
                        presetState.renderContext.textureManager->GetSampler(name), name, baseName);
                    continue;
                }]=]
)

patch_projectm_source(Renderer src/libprojectM/Renderer/TextureManager.cpp
    [=[    std::string lowerCaseName = Utils::ToLower(randomName);]=]
    [=[    std::string unqualifiedName;
    GLint wrapMode;
    GLint filterMode;
    ExtractTextureSettings(randomName, wrapMode, filterMode, unqualifiedName);
    std::string lowerCaseName = Utils::ToLower(unqualifiedName);]=]
    [=[return {desc.Texture(), desc.Sampler(), randomName, randomName};]=]
    [=[return {desc.Texture(), GetSampler(randomName), randomName, unqualifiedName};]=]
)

# Both the long, prefix-filtered name and its short randXX alias must address
# the same texture unit. Previously the alias retained its default unit (zero).
patch_projectm_source(Renderer src/libprojectM/Renderer/TextureSamplerDescriptor.cpp
    [=[namespace Renderer {]=]
    [=[namespace Renderer {

// Keep the optional filter/wrap qualifier in the short sampler alias too.
static auto RandomSamplerAlias(const std::string& name) -> std::string
{
    const size_t offset = name.size() > 3 && name[2] == '_' ? 3 : 0;
    if (name.size() > offset + 7 && name.compare(offset, 4, "rand") == 0 &&
        name[offset + 6] == '_')
    {
        return name.substr(0, offset + 6);
    }
    return {};
}]=]
    [=[        shader.SetUniformInt(std::string("sampler_" + m_samplerName).c_str(), unit);]=]
    [=[        shader.SetUniformInt(std::string("sampler_" + m_samplerName).c_str(), unit);
        const auto alias = RandomSamplerAlias(m_samplerName);
        if (!alias.empty())
        {
            shader.SetUniformInt(std::string("sampler_" + alias).c_str(), unit);
        }]=]
    [=[if (m_samplerName.substr(0, 4) == "rand" && m_samplerName.length() > 7 && m_samplerName.at(6) == '_')]=]
    [=[const auto alias = RandomSamplerAlias(m_samplerName);
    if (!alias.empty())]=]
    [=[declaration.append(m_samplerName.substr(0, 6));]=]
    [=[declaration.append(alias);]=]
)
