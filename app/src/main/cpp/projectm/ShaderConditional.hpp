#pragma once

#include <cctype>
#include <cerrno>
#include <cstdlib>
#include <functional>
#include <string>

namespace milkdrop {

// hlslparser expands macros in shader code but its #if path accepts only a
// literal integer. Evaluate feature conditions against the same macro table.
class ShaderConditional
{
public:
    using Lookup = std::function<const char*(const std::string&)>;

    static bool Evaluate(const std::string& expression, const Lookup& lookup, bool& result)
    {
        ShaderConditional parser(expression, lookup, 0);
        const auto value = parser.Or();
        parser.Space();
        result = value != 0;
        return parser.m_valid && parser.m_position == expression.size();
    }

private:
    ShaderConditional(const std::string& text, const Lookup& lookup, int depth)
        : m_text(text), m_lookup(lookup), m_depth(depth) {}

    void Space()
    {
        while (m_position < m_text.size())
        {
            if (std::isspace(static_cast<unsigned char>(m_text[m_position]))) ++m_position;
            else if (m_text.substr(m_position, 2) == "//") m_position = m_text.size();
            else if (m_text.substr(m_position, 2) == "/*")
            {
                const auto end = m_text.find("*/", m_position + 2);
                if (end == std::string::npos) { m_valid = false; return; }
                m_position = end + 2;
            }
            else break;
        }
    }

    bool Take(const std::string& token)
    {
        Space();
        if (m_text.substr(m_position, token.size()) != token) return false;
        m_position += token.size();
        return true;
    }

    std::string Name()
    {
        Space();
        const auto start = m_position;
        if (start == m_text.size() ||
            !(std::isalpha(static_cast<unsigned char>(m_text[start])) || m_text[start] == '_')) return {};
        while (m_position < m_text.size() &&
               (std::isalnum(static_cast<unsigned char>(m_text[m_position])) || m_text[m_position] == '_')) ++m_position;
        return std::string(m_text.substr(start, m_position - start));
    }

    long long Or() { auto v = And(); while (Take("||")) { const auto r = And(); v = v || r; } return v; }
    long long And() { auto v = Equal(); while (Take("&&")) { const auto r = Equal(); v = v && r; } return v; }
    long long Equal()
    {
        auto v = Relational();
        for (;;) {
            if (Take("==")) v = v == Relational();
            else if (Take("!=")) v = v != Relational();
            else return v;
        }
    }
    long long Relational()
    {
        auto v = Unary();
        for (;;) {
            if (Take("<=")) v = v <= Unary();
            else if (Take(">=")) v = v >= Unary();
            else if (Take("<")) v = v < Unary();
            else if (Take(">")) v = v > Unary();
            else return v;
        }
    }
    long long Unary()
    {
        if (++m_depth > 64) { m_valid = false; --m_depth; return 0; }
        const auto v = Take("!") ? !Unary() : Take("-") ? -Unary() : Take("+") ? Unary() : Primary();
        --m_depth;
        return v;
    }
    long long Primary()
    {
        if (Take("(")) { const auto v = Or(); if (!Take(")")) m_valid = false; return v; }
        const auto name = Name();
        if (name == "defined")
        {
            const bool parentheses = Take("(");
            const auto macro = Name();
            if (macro.empty() || (parentheses && !Take(")"))) m_valid = false;
            return !macro.empty() && m_lookup(macro) != nullptr;
        }
        if (!name.empty())
        {
            const char* value = m_lookup(name);
            if (!value) return 0; // Undefined identifiers in #if are zero.
            ShaderConditional nested(value, m_lookup, m_depth);
            const auto v = nested.Or(); nested.Space();
            m_valid = m_valid && nested.m_valid && nested.m_position == nested.m_text.size();
            return v;
        }
        Space();
        const std::string rest(m_text.substr(m_position));
        char* end{}; errno = 0;
        const auto value = std::strtoll(rest.c_str(), &end, 0);
        if (end == rest.c_str() || errno == ERANGE) { m_valid = false; return 0; }
        m_position += static_cast<std::size_t>(end - rest.c_str());
        while (m_position < m_text.size() &&
               (m_text[m_position] == 'u' || m_text[m_position] == 'U' ||
                m_text[m_position] == 'l' || m_text[m_position] == 'L')) ++m_position;
        return value;
    }

    std::string m_text;
    const Lookup& m_lookup;
    std::size_t m_position{};
    int m_depth{};
    bool m_valid{true};
};

} // namespace milkdrop
