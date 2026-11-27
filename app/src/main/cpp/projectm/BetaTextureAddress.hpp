#pragma once

#include <Renderer/Sampler.hpp>

namespace milkdrop {

inline GLint BetaTextureAddress(double mode, GLint inherited)
{
    if (mode == -1.0) return inherited;
    if (mode == 1.0) return GL_REPEAT;
    if (mode == 2.0) return GL_MIRRORED_REPEAT;
    return GL_CLAMP_TO_EDGE;
}

}

