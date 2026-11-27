#pragma once

#include <string>

namespace milkdrop {

// MilkDrop computes mesh zoom with CPU powf(). GLSL pow() is undefined for a
// negative base even when the exponent is 1, a common legacy preset technique.
inline std::string CompatibleWarpVertexShader(std::string source, bool legacyDecay = false)
{
    const std::string original = "float zoom2 = pow(zoom, pow(zoomExp, radius * 2.0 - 1.0));";
    const auto at = source.find(original);
    if (at != std::string::npos)
    {
        source.replace(at, original.size(), R"(
    float zoomPower = pow(zoomExp, radius * 2.0 - 1.0);
    float zoom2;
    if (zoomPower == 1.0) {
        zoom2 = zoom;
    } else if (zoom < 0.0 && zoomPower == floor(zoomPower)) {
        float magnitude = pow(-zoom, zoomPower);
        zoom2 = mod(abs(zoomPower), 2.0) == 1.0 ? -magnitude : magnitude;
    } else {
        zoom2 = pow(zoom, zoomPower);
    }
)");
    }

    const std::string polarOutput = "frag_TEXCOORD1 = rad_ang;";
    const auto polarAt = source.find(polarOutput);
    if (polarAt != std::string::npos)
    {
        source.replace(polarAt, polarOutput.size(), R"(
    // Preset angles use a positive Y axis toward the top of the image.
    // Each half needs its own endpoint on the negative X axis.
    float presetAngle = -rad_ang.y;
    if (pos.y == 0.0 && pos.x < 0.0) {
        presetAngle = milkdropWarpSeamAngle;
    }
    frag_TEXCOORD1 = vec2(rad_ang.x, presetAngle);
)");
        source.insert(source.find("out vec2 frag_TEXCOORD1;"), "uniform float milkdropWarpSeamAngle;\n");
    }
    if (legacyDecay)
    {
        const auto input = source.find("out vec2 frag_TEXCOORD1;");
        const auto output = source.find("frag_COLOR = vec4(decay, decay, decay, 1.0);");
        if (input != std::string::npos && output != std::string::npos)
        {
            source.replace(output, std::string("frag_COLOR = vec4(decay, decay, decay, 1.0);").size(),
                           "frag_COLOR = legacy_decay;");
            source.insert(input, "layout(location = 8) in vec4 legacy_decay;\n");
        }
    }
    return source;
}

} // namespace milkdrop
