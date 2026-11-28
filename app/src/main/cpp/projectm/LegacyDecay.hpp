#pragma once
#include <cstdint>

namespace milkdrop {

// MilkDrop scales in single precision and keeps the low DWORD after truncation.
inline std::uint32_t LegacyDecayBits(double value)
{
    const float scaled = static_cast<float>(value) * 255.0f;
    constexpr float int64Limit = 0x1p63f;
    if (scaled >= -int64Limit && scaled < int64Limit)
        return static_cast<std::uint32_t>(static_cast<std::int64_t>(scaled));
    return 0;
}

inline std::uint32_t PackLegacyDecay(double red, double green, double blue)
{
    // Oversized values spill into neighboring channels in the original
    // packing. Keep that behavior while leaving the warp alpha independent.
    return ((LegacyDecayBits(red) << 16) |
            (LegacyDecayBits(green) << 8) |
             LegacyDecayBits(blue)) & 0x00ffffffu;
}

}
