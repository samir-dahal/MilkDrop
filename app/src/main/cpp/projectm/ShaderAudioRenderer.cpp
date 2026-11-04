#include "ShaderAudioRenderer.hpp"

#include <algorithm>
#include <cmath>

namespace milkdrop {

ShaderAudioTexture::~ShaderAudioTexture()
{
    if (m_texture) glDeleteTextures(1, &m_texture);
}

void ShaderAudioTexture::Bind(const ShaderAudioFrame& audio, const libprojectM::Renderer::Shader& shader,
                             GLint textureUnit, float attack, float decay)
{
    GLint program{};
    glGetIntegerv(GL_CURRENT_PROGRAM, &program);
    const GLint samplerLocation = glGetUniformLocation(program, "_milkdrop_audio");
    if (!audio.smoothing) return;
    auto& smoothing = *audio.smoothing;
    // Preset controls feed the next engine update; processing itself runs even
    // when this shader does not use audio functions.
    smoothing.attack = std::isfinite(attack) ? std::clamp(attack, 0.0f, 1.0f) : 0.5f;
    smoothing.decay = std::isfinite(decay) ? std::clamp(decay, 0.0f, 1.0f) : 0.7f;
    if (samplerLocation < 0) return;

    GLint maxUnits{};
    glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, &maxUnits);
    if (textureUnit >= maxUnits)
        throw libprojectM::Renderer::ShaderException("Audio shader requires one free fragment texture unit.");

    for (std::size_t i = 0; i < ShaderFftBins; ++i)
        m_pixels[i] = {smoothing.levels[i], smoothing.peaks[i],
                       i < ShaderWaveSamples ? audio.waveLeft[i] : 0.0f,
                       i < ShaderWaveSamples ? audio.waveRight[i] : 0.0f};

    glActiveTexture(GL_TEXTURE0 + textureUnit);
    if (!m_texture)
    {
        glGenTextures(1, &m_texture);
        glBindTexture(GL_TEXTURE_2D, m_texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, ShaderFftBins, 1, 0, GL_RGBA, GL_FLOAT, m_pixels.data());
    }
    else
    {
        glBindTexture(GL_TEXTURE_2D, m_texture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, ShaderFftBins, 1, GL_RGBA, GL_FLOAT, m_pixels.data());
    }
    glBindSampler(textureUnit, 0);
    glUniform1i(samplerLocation, textureUnit);
    shader.SetUniformFloat("_milkdrop_audio_nyquist", std::max(audio.sampleRate * 0.5f, 1.0f));
}

} // namespace milkdrop
