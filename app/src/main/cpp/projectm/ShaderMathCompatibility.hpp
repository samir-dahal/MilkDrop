#pragma once
#include <string>
#include <regex>

namespace milkdrop {
// HLSL frexp uses floating-point output exponents, unlike GLSL's integer output.
// Explicit zero handling also keeps legacy feedback shaders free of NaNs.
inline std::string CompatibleShaderMath(std::string source)
{
    source = std::regex_replace(source, std::regex(R"(\bfrexp\s*\()"), "_milkdrop_frexp(");
    source = std::regex_replace(source, std::regex(R"(\bnormalize\s*\()"), "_milkdrop_normalize(");
    source = std::regex_replace(source, std::regex(R"(\ball\s*\()"), "_milkdrop_all(");
    source = std::regex_replace(source, std::regex(R"(\bany\s*\()"), "_milkdrop_any(");
    std::string helpers = R"(
float _milkdrop_frexp(float x, out float e) {
    if (x == 0) { e = 0; return 0; }
    e = floor(log2(abs(x))) + 1;
    return (x / exp2(e - 1)) * 0.5;
}
float _milkdrop_normalize(float x) { return x == 0 ? 0 : sign(x); }
)";
    for (int size = 2; size <= 4; ++size) {
        const auto type = "float" + std::to_string(size);
        helpers += type + " _milkdrop_frexp(" + type + " x, out " + type + " e) { " + type + " m;";
        for (int i = 0; i < size; ++i) {
            const std::string channel(1, "xyzw"[i]);
            helpers += "m." + channel + " = _milkdrop_frexp(x." + channel + ", e." + channel + ");";
        }
        helpers += "return m; }\n";
        helpers += type + " _milkdrop_normalize(" + type + " x) { float n = length(x); if (n == 0) return 0; return x / n; }\n";
    }
    for (const std::string base : {"float", "bool"}) {
        for (int count = 1; count <= 4; ++count) {
            const auto type = base + (count == 1 ? "" : std::to_string(count));
            for (bool every : {false, true}) {
                helpers += "bool _milkdrop_" + std::string(every ? "all" : "any") + "(" + type + " x) { return ";
                for (int i = 0; i < count; ++i) {
                    if (i) helpers += every ? " && " : " || ";
                    helpers += "(x" + (count == 1 ? std::string() : "." + std::string(1, "xyzw"[i])) + " != 0)";
                }
                helpers += "; }\n";
            }
        }
    }
    return helpers + source;
}
} // namespace milkdrop
