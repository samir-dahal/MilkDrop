#pragma once

#include <chrono>
#include <cmath>
#include <ctime>
#include <mutex>

namespace milkdrop {

struct ShaderInputValues
{
    float mouseX{-1.0f};
    float mouseY{-1.0f}; // Screen coordinates, top left origin.
    float clickX{0.0f};
    float clickY{0.0f};
    bool down{false};
    bool pressed{false};
    int year{}, month{}, day{}, weekday{};
    int hour{}, minute{}, second{}, milliseconds{};
    double totalSeconds{};
};

// Owned by one projectM instance. No global pointer or VM-variable caches.
class ShaderInputsState
{
public:
    ShaderInputsState()
    {
        UpdateClock(m_values);
    }

    void SetPointer(float x, float y, bool down, bool pressed)
    {
        const std::lock_guard<std::mutex> lock(m_mutex);
        const bool inside = std::isfinite(x) && std::isfinite(y) &&
                            x >= 0.0f && x <= 1.0f && y >= 0.0f && y <= 1.0f;
        m_values.mouseX = inside ? x : -1.0f;
        m_values.mouseY = inside ? y : -1.0f;
        if (inside && pressed)
        {
            m_values.clickX = x;
            m_values.clickY = y;
        }
        m_values.down = down;
        // The app submits one pointer snapshot before applying queued preset changes.
        // Clear the preceding frame's pulse so init code cannot observe it again.
        m_values.pressed = false;
        m_pendingPress = m_pendingPress || pressed;
        UpdateClock(m_values);
    }

    // Called once per rendered frame, before either preset in a transition.
    void BeginFrame()
    {
        const std::lock_guard<std::mutex> lock(m_mutex);
        m_values.pressed = m_pendingPress;
        m_pendingPress = false;
        UpdateClock(m_values);
    }

    ShaderInputValues Snapshot()
    {
        const std::lock_guard<std::mutex> lock(m_mutex);
        auto result = m_values;
        // Preset initialization can happen before the first rendered frame.
        result.pressed = result.pressed || m_pendingPress;
        return result;
    }

private:
    static void UpdateClock(ShaderInputValues& result)
    {
        const auto now = std::chrono::system_clock::now();
        const auto seconds = std::chrono::time_point_cast<std::chrono::seconds>(now);
        result.milliseconds = static_cast<int>(
            std::chrono::duration_cast<std::chrono::milliseconds>(now - seconds).count());
        const auto timestamp = std::chrono::system_clock::to_time_t(seconds);
        std::tm local{};
#if defined(_WIN32)
        const bool valid = localtime_s(&local, &timestamp) == 0;
#else
        const bool valid = localtime_r(&timestamp, &local) != nullptr;
#endif
        if (valid)
        {
            result.year = local.tm_year + 1900;
            result.month = local.tm_mon + 1;
            result.day = local.tm_mday;
            result.weekday = local.tm_wday == 0 ? 7 : local.tm_wday;
            result.hour = local.tm_hour;
            result.minute = local.tm_min;
            result.second = local.tm_sec;
            result.totalSeconds = result.hour * 3600.0 + result.minute * 60.0 +
                                  result.second + result.milliseconds * 0.001;
        }
    }
    std::mutex m_mutex;
    ShaderInputValues m_values;
    bool m_pendingPress{false};
};

} // namespace milkdrop
