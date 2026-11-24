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
float _milkdrop_frexp(float x, out int e) {
    float exponent;
    float mantissa = _milkdrop_frexp(x, exponent);
    e = int(exponent);
    return mantissa;
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
        const auto intType = "int" + std::to_string(size);
        helpers += type + " _milkdrop_frexp(" + type + " x, out " + intType + " e) { " + type +
            " exponent; " + type + " m = _milkdrop_frexp(x, exponent); e = " + intType + "(exponent); return m; }\n";
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
    // Keep invalid angles from propagating into an otherwise valid pixel.
    // Apply the fallback per component so valid lanes keep their original values.
    static const std::regex sincosCall(R"(\bsincos\s*\()");
    const bool usesSincos = std::regex_search(source, sincosCall);
    for (const std::string name : {"sin", "cos", "tan", "asin", "acos", "atan", "atan2"}) {
        const std::regex call("\\b" + name + R"(\s*\()");
        const bool called = std::regex_search(source, call);
        if (!called && !(usesSincos && (name == "sin" || name == "cos"))) continue;

        const std::string helper = "_milkdrop_" + name;
        const bool binary = name == "atan2";
        if (called) source = std::regex_replace(source, call, helper + "(");
        helpers += "float " + helper + "(float x" + (binary ? ", float y" : "") + ") {\n";
        std::string invalid = "isnan(x) || isinf(x)";
        if (binary) invalid += " || isnan(y) || isinf(y) || (x == 0.0 && y == 0.0)";
        helpers += "    if (" + invalid + ") return 0.0;\n";
        if (name == "asin" || name == "acos") {
            // Direct3D's legacy shader uses rsq/rcp, which takes the magnitude
            // of 1 - abs(x) and extends the approximation beyond [-1, 1].
            helpers += R"(    if (abs(x) > 1.0) {
        float a = abs(x);
        float arc = (((-0.0187293 * a + 0.074261) * a - 0.2121144) * a + 1.5707288) * sqrt(a - 1.0);
        arc = x < 0.0 ? 3.14159265 - arc : arc;
)";
            helpers += name == "asin" ? "        return 1.57079633 - arc;\n" : "        return arc;\n";
            helpers += "    }\n";
        }
        helpers += "    return " + name + (binary ? "(x,y);\n}\n" : "(x);\n}\n");

        for (int size = 2; size <= 4; ++size) {
            const auto type = "float" + std::to_string(size);
            helpers += type + " " + helper + "(" + type + " x" + (binary ? ", " + type + " y" : "") +
                       ") { return " + type + "(";
            for (int i = 0; i < size; ++i) {
                const std::string channel(1, "xyzw"[i]);
                if (i > 0) helpers += ", ";
                helpers += helper + "(x." + channel + (binary ? ", y." + channel : "") + ")";
            }
            helpers += "); }\n";
        }
    }
    if (usesSincos) {
        source = std::regex_replace(source, sincosCall, "_milkdrop_sincos(");
        for (const std::string type : {"float", "float2", "float3", "float4"}) {
            helpers += "void _milkdrop_sincos(" + type + " x, out " + type + " s, out " + type +
                       " c) { s = _milkdrop_sin(x); c = _milkdrop_cos(x); }\n";
        }
    }
    return helpers + source;
}
} // namespace milkdrop
