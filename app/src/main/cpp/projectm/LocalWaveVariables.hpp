#pragma once

#include <algorithm>
#include <cctype>
#include <string_view>

namespace milkdrop {

// PresetFileParser has already stripped comments. Bare w1–w8 assignments
// predate waveform feedback, so those variables retain their evaluator state.
inline unsigned FindLocalWaveVariables(std::string_view code)
{
    constexpr std::string_view whitespace = " \t\r\n\v\f";
    const auto isNameChar = [](unsigned char ch) {
        return std::isalnum(ch) || ch == '_' || ch == '.';
    };
    const auto waveBit = [](std::string_view name) -> unsigned {
        if (name.size() == 2 && (name[0] == 'w' || name[0] == 'W') &&
            name[1] >= '1' && name[1] <= '8')
            return 1u << (name[1] - '1');
        return 0;
    };

    unsigned result = 0;
    for (std::size_t pos = 0; pos < code.size();)
    {
        const auto start = pos++;
        if (!isNameChar(code[start]))
            continue;
        while (pos < code.size() && isNameChar(code[pos]))
            ++pos;

        const auto name = code.substr(start, pos - start);
        const auto next = code.find_first_not_of(whitespace, pos);
        if (next == code.npos)
            break;

        const auto op = code.substr(next, 2);
        if ((op.front() == '=' && op != "==") ||
            (op.size() == 2 && op[1] == '=' && std::string_view("+-*/%^&|").find(op[0]) != code.npos))
        {
            result |= waveBit(name);
        }
        else if (op.front() == '(' && name.size() == 6 &&
                 std::equal(name.begin(), name.end(), "assign", [](unsigned char a, char b) {
                     return std::tolower(a) == b;
                 }))
        {
            const auto lhs = code.find_first_not_of(whitespace, next + 1);
            if (lhs == code.npos || code.size() - lhs < 3)
                continue;
            const auto comma = code.find_first_not_of(whitespace, lhs + 2);
            if (comma != code.npos && code[comma] == ',')
                result |= waveBit(code.substr(lhs, 2));
        }
    }
    return result;
}

}
