#pragma once

#include "ShaderAudioData.hpp"

#include <Renderer/Shader.hpp>

#include <array>
#include <cstdint>

namespace milkdrop {

// One compact floating-point texture per compiled shader, released with that shader.
// Nearest texture sampling plus explicit interpolation works on baseline GLES 3.0,
// including GPUs without support for linearly filtering 32-bit float textures.
class ShaderAudioTexture
{
public:
    ShaderAudioTexture() = default;
    ~ShaderAudioTexture();
    ShaderAudioTexture(const ShaderAudioTexture&) = delete;
    ShaderAudioTexture& operator=(const ShaderAudioTexture&) = delete;

    void Bind(const ShaderAudioFrame& audio, const libprojectM::Renderer::Shader& shader,
              GLint textureUnit, float attack, float decay);

private:
    GLuint m_texture{};
    std::array<std::array<float, 4>, ShaderFftBins> m_pixels{};
};

// Injected into the HLSL header before transpilation. Texture coordinates are
// sampled with the same half-texel interpolation and clamping as BeatDrop.
inline constexpr const char* ShaderAudioFunctions = R"(
uniform float _milkdrop_audio_nyquist;
#define HAS_FFT_PEAK 1
float4 _milkdrop_audio_sample(float pos, float count)
{
    float x = clamp(saturate(pos) * count - 0.5, 0.0, count - 1.0);
    float a = floor(x);
    float b = min(a + 1.0, count - 1.0);
    return lerp(tex2D(_milkdrop_audio, float2((a + 0.5) / 1024.0, 0.5)),
                tex2D(_milkdrop_audio, float2((b + 0.5) / 1024.0, 0.5)), x - a);
}
// Keep normalized presets on MilkDrop's 44.1 kHz frequency axis even when
// Bluetooth supplies 48/88.2/96 kHz snapshots. Hz queries use the actual rate.
float _milkdrop_fft_pos(float pos) { return saturate(pos) * min(22050.0, _milkdrop_audio_nyquist) / _milkdrop_audio_nyquist; }
float get_fft(float pos) { return sqrt(max(_milkdrop_audio_sample(_milkdrop_fft_pos(pos), 1024.0).x, 0.0)); }
float get_fft_hz(float hz) { return sqrt(max(_milkdrop_audio_sample(hz / _milkdrop_audio_nyquist, 1024.0).x, 0.0)); }
float get_fft_peak(float pos) { return sqrt(max(_milkdrop_audio_sample(_milkdrop_fft_pos(pos), 1024.0).y, 0.0)); }
float get_fft_peak_hz(float hz) { return sqrt(max(_milkdrop_audio_sample(hz / _milkdrop_audio_nyquist, 1024.0).y, 0.0)); }
float get_wave_left(float pos) { return _milkdrop_audio_sample(pos, 576.0).z; }
float get_wave_right(float pos) { return _milkdrop_audio_sample(pos, 576.0).w; }
float get_wave(float pos)
{
    float4 waveSample = _milkdrop_audio_sample(pos, 576.0);
    return (waveSample.z + waveSample.w) * 0.5;
}
)";

} // namespace milkdrop
