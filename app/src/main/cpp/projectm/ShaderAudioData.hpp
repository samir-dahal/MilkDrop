#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace milkdrop {

inline constexpr std::size_t ShaderFftBins = 1024;
inline constexpr std::size_t ShaderFftWindow = ShaderFftBins * 2;
inline constexpr std::size_t ShaderWaveSamples = 576;

// Shared by all presets of one PCM instance so transitions and preset switches
// retain the same peak history. Only the render thread reads/writes these fields.
struct ShaderAudioSmoothing
{
    bool hasFrame{};
    std::uint32_t frame{};
    float rms{0.1f};
    float attack{0.5f};
    float decay{0.7f};
    std::array<float, ShaderFftBins> levels{};
    std::array<float, ShaderFftBins> peaks{};
    std::array<float, ShaderFftBins> holdSeconds{};
};

// A render-frame snapshot; PCM owns the input buffer and every shader owns its GPU state.
struct ShaderAudioFrame
{
    std::array<float, ShaderFftBins> spectrum{};
    std::array<float, ShaderWaveSamples> waveLeft{};
    std::array<float, ShaderWaveSamples> waveRight{};
    float sampleRate{44100.0f};
    float scaling{0.45f};
    float noiseFloor{0.1f};
    std::shared_ptr<ShaderAudioSmoothing> smoothing;
};

} // namespace milkdrop
