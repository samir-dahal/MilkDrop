#pragma once
#include <cmath>
#include <cstdint>

namespace milkdrop {
// MilkDrop converts to float before multiplying, truncates to a signed DWORD,
// then D3DCOLOR_RGBA masks each channel to eight bits. Do not saturate first:
// legacy flame presets intentionally rely on the wrapped result.
inline float PackedLegacyDecay(double value)
{
    const float scaled = static_cast<float>(value) * 255.0f;
    // Match the x86 indefinite integer result without undefined C++ conversions.
    if (!std::isfinite(scaled) || scaled < -2147483648.0f || scaled >= 2147483648.0f)
        return 0.0f;
    const auto packed = static_cast<std::uint32_t>(static_cast<std::int32_t>(scaled));
    return static_cast<float>(packed & 255u) / 255.0f;
}
}
