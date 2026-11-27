#pragma once

#include <Audio/AudioConstants.hpp>
#include <Audio/MilkdropFFT.hpp>

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace milkdrop {

class BetaSoundScope
{
    using Waveform = libprojectM::Audio::WaveformBuffer;
    static constexpr int BinCount = 512;

public:
    static void Prepare(const Waveform& left, const Waveform& right)
    {
        s_left = &left;
        s_right = &right;
    }

    BetaSoundScope() : m_left(s_left), m_right(s_right), m_previous(s_active)
    {
        s_active = this;
    }

    ~BetaSoundScope()
    {
        s_active = m_previous;
    }

    BetaSoundScope(const BetaSoundScope&) = delete;
    BetaSoundScope& operator=(const BetaSoundScope&) = delete;

    static double Sum(double first, double last)
    {
        if (!s_active || !s_active->m_left || !std::isfinite(first) || !std::isfinite(last)) return 0.0;
        const int begin = static_cast<int>(std::clamp(std::min(first, last), 0.0, double(BinCount)));
        const int end = static_cast<int>(std::clamp(std::max(first, last), 0.0, double(BinCount)));
        if (begin == end) return 0.0;
        s_active->BuildSpectrum();
        return s_active->m_sums[end] - s_active->m_sums[begin];
    }

private:
    void BuildSpectrum()
    {
        if (m_ready) return;

        // The beta uses 576 samples and 512 equalized stereo FFT bins.
        static thread_local libprojectM::Audio::MilkdropFFT fft(576, BinCount);
        static thread_local std::vector<float> input, left, right;
        input.assign(m_left->begin(), m_left->end());
        fft.TimeToFrequencyDomain(input, left);
        input.assign(m_right->begin(), m_right->end());
        fft.TimeToFrequencyDomain(input, right);
        for (int bin = 0; bin < BinCount; ++bin)
        {
            const float l = std::isfinite(left[bin]) ? left[bin] : 0.0f;
            const float r = std::isfinite(right[bin]) ? right[bin] : 0.0f;
            m_sums[bin + 1] = m_sums[bin] + (static_cast<double>(l) + r) * 0.5;
        }
        m_ready = true;
    }

    const Waveform* m_left;
    const Waveform* m_right;
    BetaSoundScope* m_previous;
    std::array<double, BinCount + 1> m_sums{};
    bool m_ready{false};
    static inline thread_local const Waveform* s_left = nullptr;
    static inline thread_local const Waveform* s_right = nullptr;
    static inline thread_local BetaSoundScope* s_active = nullptr;
};

}

