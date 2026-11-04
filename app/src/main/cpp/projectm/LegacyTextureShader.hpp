#pragma once
#include <string>

namespace milkdrop {

// D3D9's fixed-function shape pass modulates texture RGB but selects diffuse
// alpha. Feedback texture alpha is render-target bookkeeping, not opacity.
// Named images, including the idle logo, still need their own transparency.
inline std::string LegacyTextureShader(std::string source)
{
    const std::string original = "color = fragment_color * texture(texture_sampler, fragment_texture.st);";
    const auto at = source.find(original);
    if (at != std::string::npos)
    {
        source.replace(at, original.size(),
            "vec4 shape_texture = texture(texture_sampler, fragment_texture.st);\n"
            "color = vec4(fragment_color.rgb * shape_texture.rgb, fragment_color.a * "
            "mix(1.0, shape_texture.a, milkdrop_image_alpha));");
        const auto mainAt = source.find("void main()");
        if (mainAt != std::string::npos)
            source.insert(mainAt, "uniform float milkdrop_image_alpha;\n");
    }
    return source;
}

} // namespace milkdrop
