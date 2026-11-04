#pragma once

#include <Renderer/RenderContext.hpp>

namespace milkdrop {

// Derive the physical aspect from the viewport, independently of the render mode.
// Shader `aspect` must continue to describe the real screen in both modes.
inline auto PhysicalAspect(const libprojectM::Renderer::RenderContext& context)
    -> libprojectM::Renderer::RenderContext
{
    auto physical = context;
    const auto width = context.viewportSizeX;
    const auto height = context.viewportSizeY;
    physical.aspectX = height > width && height > 0 ? static_cast<float>(width) / height : 1.0f;
    physical.aspectY = width > height && width > 0 ? static_cast<float>(height) / width : 1.0f;
    physical.invAspectX = physical.aspectX > 0 ? 1.0f / physical.aspectX : 1.0f;
    physical.invAspectY = physical.aspectY > 0 ? 1.0f / physical.aspectY : 1.0f;
    return physical;
}

inline void ApplyScreenDependentAspect(libprojectM::Renderer::RenderContext& context, bool enabled)
{
    if (enabled)
    {
        context.aspectX = context.aspectY = context.invAspectX = context.invAspectY = 1.0f;
    }
}

} // namespace milkdrop
