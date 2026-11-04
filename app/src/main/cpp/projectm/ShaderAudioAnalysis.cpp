#include "ShaderAudioAnalysis.hpp"

#include <algorithm>
#include <cmath>

namespace milkdrop {

void ShaderAudioAnalysis::SetSettings(float scaling, float noiseFloor)
{
    if (std::isfinite(scaling)) m_scaling = std::clamp(scaling, 0.0f, 2.0f);
    if (std::isfinite(noiseFloor)) m_noiseFloor = std::clamp(noiseFloor, 0.0f, 1.0f);
}

void ShaderAudioAnalysis::SetInputFormat(std::uint32_t sampleRate, bool snapshot)
{
    if (sampleRate < 8000 || sampleRate > 384000) return;
    if (m_sampleRate != static_cast<float>(sampleRate) || m_snapshot != snapshot)
    {
        m_ringLeft.fill(0.0f);
        m_ringRight.fill(0.0f);
        m_next = 0;
        m_resetSmoothing = true;
    }
    m_sampleRate = static_cast<float>(sampleRate);
    m_snapshot = snapshot;
}

void ShaderAudioAnalysis::BeginBlock()
{
    // Visualizer supplies overlapping snapshots. Concatenating them would invent
    // discontinuities; analyze only its latest complete window, with zero padding.
    if (m_snapshot)
    {
        m_ringLeft.fill(0.0f);
        m_ringRight.fill(0.0f);
        m_next = 0;
    }
}

void ShaderAudioAnalysis::Add(float left, float right)
{
    m_ringLeft[m_next] = std::isfinite(left) ? left : 0.0f;
    m_ringRight[m_next] = std::isfinite(right) ? right : 0.0f;
    m_next = (m_next + 1) % ShaderFftWindow;
}

void ShaderAudioAnalysis::CopyForFrame()
{
    if (m_resetSmoothing)
    {
        *m_frame.smoothing = ShaderAudioSmoothing{};
        m_resetSmoothing = false;
    }
    m_frame.sampleRate = m_sampleRate;
    m_frame.scaling = m_scaling;
    m_frame.noiseFloor = m_noiseFloor;
    for (std::size_t i = 0; i < ShaderFftWindow; ++i)
    {
        m_inputLeft[i] = m_ringLeft[(m_next + i) % ShaderFftWindow];
        m_inputRight[i] = m_ringRight[(m_next + i) % ShaderFftWindow];
    }
    for (std::size_t i = 0; i < ShaderWaveSamples; ++i)
    {
        m_frame.waveLeft[i] = m_inputLeft[ShaderFftWindow - ShaderWaveSamples + i] / 128.0f;
        m_frame.waveRight[i] = m_inputRight[ShaderFftWindow - ShaderWaveSamples + i] / 128.0f;
    }
}

void ShaderAudioAnalysis::Analyze(double secondsSinceLastFrame, std::uint32_t frame)
{
    // BeatDrop uses a 2048-sample Hann window, 1024 clean magnitude bins, and
    // averages magnitudes after FFT so opposite stereo phases cannot cancel.
    m_fft.TimeToFrequencyDomain(m_inputLeft, m_spectrumLeft);
    m_fft.TimeToFrequencyDomain(m_inputRight, m_spectrumRight);
    for (std::size_t i = 0; i < ShaderFftBins; ++i)
        m_frame.spectrum[i] = (m_spectrumLeft[i] + m_spectrumRight[i]) * 0.5f;

    // Advance on every engine frame, including presets without FFT shaders, so
    // a later preset cannot inherit frozen levels or peak-hold timers.
    auto& smoothing = *m_frame.smoothing;
    smoothing.frame = frame;
    smoothing.hasFrame = true;
    const float dt = std::isfinite(secondsSinceLastFrame) && secondsSinceLastFrame > 0.0
        ? static_cast<float>(secondsSinceLastFrame) : 1.0f / 60.0f;
    double sumSquared{};
    for (float magnitude : m_frame.spectrum) sumSquared += double(magnitude) * magnitude;
    const float rms = std::max(static_cast<float>(std::sqrt(sumSquared / ShaderFftBins)), 1e-5f);
    const float rmsDecay = std::exp(-dt / (rms > smoothing.rms ? 2.0f : 5.0f));
    smoothing.rms = smoothing.rms * rmsDecay + rms * (1.0f - rmsDecay);
    const float attackPerFrame = 1.0f - std::pow(1.0f - smoothing.attack, 60.0f * dt);
    const float decayFactor = (1.0f - smoothing.decay) * (1.0f - smoothing.decay);
    const float decayPerFrame = 1.0f - std::pow(1.0f - decayFactor, 60.0f * dt);
    for (std::size_t i = 0; i < ShaderFftBins; ++i)
    {
        float magnitude = std::max(m_frame.spectrum[i] / smoothing.rms * m_frame.scaling - m_frame.noiseFloor, 0.0f);
        const float lowBins = 24.0f * 44100.0f / m_sampleRate;
        if (static_cast<float>(i) < lowBins)
        {
            const float t = static_cast<float>(i) / lowBins;
            magnitude *= 0.15f + 0.85f * t * t;
        }
        smoothing.levels[i] += (magnitude - smoothing.levels[i]) *
            (magnitude > smoothing.levels[i] ? attackPerFrame : decayPerFrame);
        if (smoothing.levels[i] < 5e-8f) smoothing.levels[i] = 0.0f;
        if (smoothing.levels[i] >= smoothing.peaks[i])
        {
            smoothing.peaks[i] = smoothing.levels[i];
            smoothing.holdSeconds[i] = 0.25f + smoothing.decay * 0.75f;
        }
        else
        {
            const float fallingTime = std::max(dt - smoothing.holdSeconds[i], 0.0f);
            smoothing.holdSeconds[i] = std::max(smoothing.holdSeconds[i] - dt, 0.0f);
            smoothing.peaks[i] *= std::exp(-fallingTime / 0.3f);
        }
    }
}

} // namespace milkdrop
