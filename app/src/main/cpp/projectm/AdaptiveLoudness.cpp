#include "Audio/Loudness.hpp"

#include <algorithm>
#include <cmath>

namespace libprojectM {
namespace Audio {

Loudness::Loudness(Band band)
{
    using namespace BeatAnalysisConfig;
    const double edges[] = {BassLow, BassHigh, MidHigh, TrebleHigh};
    const auto b = static_cast<unsigned>(band);
    const double binHz = SampleRate / (2 * SpectrumSamples);
    // BeatDrop uses inclusive integer bin ranges, including the shared crossover.
    m_firstBin = std::clamp(static_cast<int>(edges[b] / binHz), 0, SpectrumSamples - 1);
    m_lastBin = std::clamp(static_cast<int>(edges[b + 1] / binHz), 0, SpectrumSamples - 1);
}

void Loudness::Update(const std::array<float, SpectrumSamples>& spectrum, double elapsed, uint32_t frame)
{
    if (!std::isfinite(elapsed) || elapsed <= 0) return;
    double current = 0;
    for (int i = m_firstBin; i <= m_lastBin; ++i)
        if (std::isfinite(spectrum[i])) current += std::max(0.0f, spectrum[i]);

    // Average raw bands before division, as MilkDrop/BeatDrop does. RMS/flux
    // enhancement and soft ceilings change cross-band ratios used by presets.
    const double averageRate = std::pow(current > m_average ? 0.2 : 0.5, elapsed * 30.0);
    m_average = m_average * averageRate + current * (1.0 - averageRate);
    const double referenceRate = std::pow(frame < 50 ? 0.9 : 0.992, elapsed * 30.0);
    m_longAverage = m_longAverage * referenceRate + current * (1.0 - referenceRate);
    if (m_longAverage < 0.001)
    {
        m_relative = 0;
        m_attenuated = 0;
        return;
    }
    m_relative = static_cast<float>(current / m_longAverage);
    m_attenuated = static_cast<float>(m_average / m_longAverage);
}

} // namespace Audio
} // namespace libprojectM
