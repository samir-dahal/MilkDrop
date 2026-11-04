#pragma once

#include "ShaderAudioData.hpp"

#include <Audio/MilkdropFFT.hpp>

#include <array>
#include <cstdint>
#include <vector>

namespace milkdrop {

// Input/format/settings methods run under PCM's mutex. Analyze and Frame run on the
// render thread, after CopyForFrame takes a complete snapshot under that mutex.
class ShaderAudioAnalysis
{
public:
    ShaderAudioAnalysis() { m_frame.smoothing = std::make_shared<ShaderAudioSmoothing>(); }
    void SetSettings(float scaling, float noiseFloor);
    void SetInputFormat(std::uint32_t sampleRate, bool snapshot);
    void BeginBlock();
    void Add(float left, float right);
    void CopyForFrame();
    void Analyze(double secondsSinceLastFrame, std::uint32_t frame);
    const ShaderAudioFrame& Frame() const { return m_frame; }

private:
    std::array<float, ShaderFftWindow> m_ringLeft{};
    std::array<float, ShaderFftWindow> m_ringRight{};
    std::size_t m_next{};
    bool m_snapshot{};
    bool m_resetSmoothing{};
    float m_sampleRate{44100.0f};
    float m_scaling{0.45f};
    float m_noiseFloor{0.1f};

    std::vector<float> m_inputLeft = std::vector<float>(ShaderFftWindow);
    std::vector<float> m_inputRight = std::vector<float>(ShaderFftWindow);
    std::vector<float> m_spectrumLeft;
    std::vector<float> m_spectrumRight;
    libprojectM::Audio::MilkdropFFT m_fft{ShaderFftWindow, ShaderFftBins, false};
    ShaderAudioFrame m_frame;
};

} // namespace milkdrop
