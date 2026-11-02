#include "Audio/Loudness.hpp"

#include <algorithm>
#include <cmath>
#include <limits>

namespace libprojectM {
namespace Audio {

Loudness::Loudness(Band band)
{
    using namespace BeatAnalysisConfig;
    const double edges[] = {BassLow, BassHigh, MidHigh, TrebleHigh};
    const auto b = static_cast<unsigned>(band);
    const double binHz = SampleRate / (2 * SpectrumSamples);
    const double low = edges[b];
    const double high = std::min(edges[b + 1], SampleRate / 2);
    // Bin zero is DC. Fractional overlap avoids abrupt changes at a crossover.
    for (int i = 1; i < SpectrumSamples; ++i)
    {
        const double binLow = (i - 0.5) * binHz;
        const double binHigh = (i + 0.5) * binHz;
        m_weights[i] = static_cast<float>(std::max(0.0, std::min(high, binHigh) - std::max(low, binLow)) / binHz);
        m_weightSum += m_weights[i];
        // PCM's public spectrum is already weighted by MilkdropFFT's legacy equalizer.
        // Undo that weighting for analysis only; preset waveforms and spectra stay intact.
        const double equalizer = -0.02 * std::log(static_cast<double>(SpectrumSamples - i) / SpectrumSamples);
        m_inverseEqualizer[i] = static_cast<float>(1.0 / equalizer);
    }
}

void Loudness::Update(const std::array<float, SpectrumSamples>& spectrum, double elapsed, uint32_t /*frame*/)
{
    using namespace BeatAnalysisConfig;
    // A zero/invalid frame duration must not poison state or manufacture an onset.
    if (!std::isfinite(elapsed) || elapsed <= 0 || m_weightSum <= 0) return;
    double power = 0;
    double positiveChange = 0;
    for (int i = 1; i < SpectrumSamples; ++i)
    {
        if (m_weights[i] == 0) continue;
        const float value = std::isfinite(spectrum[i]) ? std::max(0.0f, spectrum[i]) * m_inverseEqualizer[i] : 0.0f;
        power += m_weights[i] * static_cast<double>(value) * value;
        positiveChange += m_weights[i] * std::max(0.0f, value - m_previous[i]);
        m_previous[i] = value;
    }
    const double level = std::sqrt(power / m_weightSum);
    const double flux = positiveChange / (m_weightSum * elapsed);
    const double release = std::exp(-elapsed / ReleaseSeconds);
    if (level <= std::numeric_limits<float>::min())
    {
        m_relative = 0;
        m_attenuated *= static_cast<float>(release);
        m_initialized = false;
        return;
    }
    if (!m_initialized)
    {
        // Seed from real audio, avoiding a divide-by-zero spike after startup or silence.
        m_baseline = level;
        m_fluxMean = 0;
        m_fluxVariance = 0;
        m_initialized = true;
        m_relative = 1;
        m_attenuated = std::max(m_attenuated, m_relative);
        return;
    }

    // Use previous statistics for this hit, then learn from it. Positive spectral flux
    // emphasizes new attacks; its learned variance rejects routine spectral jitter.
    const double excess = std::max(0.0, flux - m_fluxMean - std::sqrt(m_fluxVariance));
    const double ratio = (level + excess * elapsed) / std::max(m_baseline, static_cast<double>(std::numeric_limits<float>::min()));
    // A soft ceiling prevents a quiet-to-loud change from destabilizing preset equations.
    m_relative = static_cast<float>(MaxRelative / (1.0 + (MaxRelative - 1.0) / std::max(ratio, std::numeric_limits<double>::min())));
    // *_att is a smoothed average, not a peak envelope. BeatDrop presets compare
    // bass/bass_att (etc.) to detect attacks; forcing att >= current makes those
    // tests impossible. Match its asymmetric 30-FPS attack/release coefficients.
    const double smoothing = std::pow(m_relative > m_attenuated ? 0.2 : 0.5, elapsed * 30.0);
    m_attenuated = static_cast<float>(m_attenuated * smoothing + m_relative * (1.0 - smoothing));
    const double levelAlpha = -std::expm1(-elapsed / LevelSeconds);
    m_baseline += levelAlpha * (level - m_baseline);
    const double fluxAlpha = -std::expm1(-elapsed / FluxSeconds);
    const double delta = flux - m_fluxMean;
    m_fluxMean += fluxAlpha * delta;
    m_fluxVariance = (1.0 - fluxAlpha) * (m_fluxVariance + fluxAlpha * delta * delta);
}

} // namespace Audio
} // namespace libprojectM
