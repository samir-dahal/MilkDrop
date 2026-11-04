#pragma once

#include "ShaderInputsState.hpp"

#include <projectm-eval.h>

#include <array>

namespace milkdrop {

class ShaderInputsEval
{
public:
    void Register(projectm_eval_context* context)
    {
        constexpr const char* names[] = {
            "mousex", "mousey", "mousedown", "mouseclick",
            "year", "month", "day", "weekday", "hour", "minute", "second",
            "milliseconds", "totalseconds"};
        for (std::size_t i = 0; i < m_variables.size(); ++i)
        {
            m_variables[i] = projectm_eval_context_register_variable(context, names[i]);
        }
    }

    void Load(const ShaderInputValues& values)
    {
        // BeatDrop expressions use a bottom-left origin; shader mouse.xy uses top-left.
        const double normalizedY = values.mouseY < 0.0f ? -1.0 : 1.0 - values.mouseY;
        const double data[] = {
            values.mouseX, normalizedY, values.down ? 1.0 : 0.0,
            values.pressed ? 1.0 : 0.0, static_cast<double>(values.year),
            static_cast<double>(values.month), static_cast<double>(values.day),
            static_cast<double>(values.weekday), static_cast<double>(values.hour),
            static_cast<double>(values.minute), static_cast<double>(values.second),
            static_cast<double>(values.milliseconds), values.totalSeconds};
        for (std::size_t i = 0; i < m_variables.size(); ++i)
        {
            if (m_variables[i]) *m_variables[i] = static_cast<PRJM_EVAL_F>(data[i]);
        }
    }

    void Copy(const ShaderInputsEval& source)
    {
        for (std::size_t i = 0; i < m_variables.size(); ++i)
        {
            if (m_variables[i] && source.m_variables[i])
                *m_variables[i] = *source.m_variables[i];
        }
    }

private:
    std::array<PRJM_EVAL_F*, 13> m_variables{};
};

} // namespace milkdrop
