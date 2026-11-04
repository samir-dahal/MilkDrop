#pragma once

#include <string>

namespace milkdrop {

// MilkDrop computes mesh zoom with CPU powf(). GLSL pow() is undefined for a
// negative base even when the exponent is 1, a common legacy preset technique.
inline std::string CompatibleWarpVertexShader(std::string source)
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
    return source;
}

} // namespace milkdrop
