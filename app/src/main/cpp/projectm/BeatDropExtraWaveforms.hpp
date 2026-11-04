#pragma once

#include "Waveforms/WaveformMath.hpp"
#include "PresetState.hpp"
#include <algorithm>
#include <cmath>

namespace libprojectM::MilkdropPreset::Waveforms {

// Geometry follows BeatDrop's triangle/fireworks modes. Retain its bounds at
// triangle corners and its audio-driven burst radius without allocating GL state.
class BeatDropExtraWaveform final : public WaveformMath {
public:
    explicit BeatDropExtraWaveform(bool fireworks) : m_fireworks(fireworks) {}
    auto IsLoop() -> bool override { return true; }
    float OpacityMultiplier() const override { return m_opacity; }

protected:
    void GenerateVertices(const PresetState& state, const PerFrameContext&) override
    {
        const float time = state.renderContext.time;
        m_samples = m_fireworks ? 256 : Audio::WaveformSamples / 2;
        m_wave1Vertices.resize(m_samples);
        m_wave2Vertices.clear();
        m_opacity = 1.0f;
        if (!m_fireworks)
        {
            for (int i = 0; i < m_samples; ++i)
            {
                const float progress = static_cast<float>(i) / m_samples;
                const float angle = progress * 6.28f + time * 0.2f;
                const float normal = (std::floor(progress * 3.0f) + 0.5f) / 3.0f * 6.28f + time * 0.2f;
                float edge = std::cos(angle - normal);
                if (std::abs(edge) < 0.02f) edge = edge < 0.0f ? -0.02f : 0.02f;
                const float audio = (m_pcmDataL[i] + m_pcmDataR[i]) * 0.5f;
                const float radius = std::clamp((0.7f + edge * audio + m_mysteryWaveParam) / (2.0f * edge), -2.0f, 2.0f);
                m_wave1Vertices[i] = {radius * std::cos(angle) * m_aspectY + m_waveX,
                                      radius * std::sin(angle) * m_aspectX + m_waveY};
            }
            return;
        }

        const float frequency = std::max(0.001f, 1.001f - m_mysteryWaveParam);
        const float phase = std::fmod(time, frequency) / frequency;
        const float seed = std::floor(time / frequency) * 10.0f;
        float baseX = (seed * 0.1345f - std::floor(seed * 0.1345f)) * 2.0f - 1.0f;
        float baseY = (seed * 0.2783f - std::floor(seed * 0.2783f)) * 2.0f - 1.0f;
        if (std::fmod(seed, 1.0f) > 0.3f) { baseX *= 0.3f; baseY *= 0.3f; }
        const float size = std::min(1.0f, phase * 4.0f);
        const float fade = 1.0f - phase * phase * phase;
        const float boost = 1.0f + state.audioData.bass + state.audioData.mid;
        for (int i = 0; i < m_samples; ++i)
        {
            const float angle = static_cast<float>(i) / m_samples * 6.283185f;
            const float variation = 0.7f + 0.3f * std::fmod(seed + i * 0.1f, 1.0f);
            const int sample = (i * 3) % Audio::WaveformSamples;
            const float audio = (m_pcmDataL[sample] + m_pcmDataR[sample]) * 0.5f;
            const float distance = size * variation * (0.5f + 0.5f * audio) * boost;
            const float x = baseX + std::cos(angle) * distance + std::cos(time * 3.0f + angle) * size * 0.1f;
            const float y = baseY + std::sin(angle) * distance + std::sin(time * 3.0f + angle) * size * 0.1f;
            m_wave1Vertices[i] = {x * m_aspectY + m_waveX, y * m_aspectX + m_waveY};
            m_opacity *= fade; // BeatDrop applies burst fade once per particle.
        }
    }

private:
    bool m_fireworks;
    float m_opacity{1.0f};
};

} // namespace libprojectM::MilkdropPreset::Waveforms
