#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>

namespace milkdrop {

// BeatDrop's high-resolution timer estimates FPS from cumulative frame history.
// Audio envelopes use that estimate rather than one potentially stalled frame.
class BeatAnalysisTiming
{
public:
    double Update(double elapsed)
    {
        if (!std::isfinite(elapsed) || elapsed <= 0) return 1.0 / m_fps;
        const std::size_t slots = m_frames > m_history.size()
            ? static_cast<std::size_t>(64 * (m_fps < 60 ? 0.1 + 0.9 * m_fps / 60 : 1.0))
            : 0;
        if (m_frames < 2) elapsed = 1.0 / 30.0;
        else if (elapsed > 1.0 || (slots && elapsed > 5.0 / m_fps))
            elapsed = slots ? 1.0 / 30.0 : 1.0 / m_fps;

        const double previous = m_history[(m_next + m_history.size() - 1) % m_history.size()];
        const std::size_t count = std::max<std::size_t>(1, slots);
        const double start = slots ? m_history[(m_next + m_history.size() - count) % m_history.size()] : m_history[0];
        const double now = previous + elapsed;
        m_history[m_next] = now;
        m_next = (m_next + 1) % m_history.size();
        if (m_frames && now > start)
        {
            const double measured = (slots ? count : m_frames) / (now - start);
            if (slots && std::abs(m_fps - measured) > 3.0) m_fps = measured;
            else
            {
                const double damping = slots ? 0.87 : 0.6;
                m_fps = damping * m_fps + (1.0 - damping) * measured;
            }
        }
        ++m_frames;
        return 1.0 / m_fps;
    }

private:
    std::array<double, 128> m_history{};
    std::size_t m_next{};
    std::size_t m_frames{};
    double m_fps{60.0};
};

} // namespace milkdrop
