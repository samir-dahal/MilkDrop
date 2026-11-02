# BeatDrop-compatible shader FFT and waveform input, applied to build copies.
# Requires ScreenDependentRendering.cmake's sequential patch_projectm_copy helper.
set(MILKDROP_SHADER_AUDIO_DIR "${CMAKE_CURRENT_LIST_DIR}")

# Audio textures contain signed waves and FFT values above one; GLES's default
# low-precision sampler can lose or clamp that range on mobile drivers.
patch_projectm_copy(hlslparser vendor/hlslparser/src/GLSLGenerator.cpp
    [=[        m_writer.WriteLine(0, "precision highp sampler3D;");]=]
    [=[        m_writer.WriteLine(0, "precision highp sampler3D;");
        m_writer.WriteLine(0, "precision highp sampler2D;");]=]
)

patch_projectm_source(Audio src/libprojectM/Audio/FrameAudioData.hpp
    [=[#include "Audio/AudioConstants.hpp"]=]
    [=[#include "Audio/AudioConstants.hpp"
#include "ShaderAudioData.hpp"]=]
    [=[    float bass{0.f};]=]
    [=[    milkdrop::ShaderAudioFrame shaderAudio;
    float bass{0.f};]=]
)

patch_projectm_source(Audio src/libprojectM/Audio/PCM.hpp
    [=[#include "Audio/AudioConstants.hpp"]=]
    [=[#include "Audio/AudioConstants.hpp"
#include "ShaderAudioAnalysis.hpp"]=]
    [=[    auto GetFrameAudioData() const -> FrameAudioData;]=]
    [=[    auto GetFrameAudioData() const -> FrameAudioData;

    void SetShaderAudioSettings(float scaling, float noiseFloor)
    {
        std::lock_guard<std::mutex> lock(m_pcmMutex);
        m_shaderAudioInput.SetSettings(scaling, noiseFloor);
    }
    void SetShaderAudioInputFormat(uint32_t sampleRate, bool snapshot)
    {
        std::lock_guard<std::mutex> lock(m_pcmMutex);
        m_shaderAudioInput.SetInputFormat(sampleRate, snapshot);
    }]=]
    [=[    std::mutex m_pcmMutex;]=]
    [=[    milkdrop::ShaderAudioAnalysis m_shaderAudioInput;
    // BeatDrop analyzes 576 captured samples in a zero-padded 1024-sample window.
    // Keep this separate from the public custom-wave spectrum and shader FFT.
    MilkdropFFT m_beatFFT{SpectrumSamples * 2, SpectrumSamples, true};
    std::mutex m_pcmMutex;]=]
)

patch_projectm_source(Audio src/libprojectM/Audio/PCM.cpp
    [=[    // Beat detection should reflect both channels; average L+R into a mono spectrum.
    SpectrumBuffer monoSpectrum{};
    for (size_t sample = 0; sample < SpectrumSamples; sample++)
    {
        monoSpectrum[sample] = 0.5f * (m_spectrumL[sample] + m_spectrumR[sample]);
    }]=]
    [=[    // Use BeatDrop's unfiltered, zero-padded capture for beat variables only.
    static_assert(AudioBufferSamples <= SpectrumSamples * 2, "Beat FFT window is smaller than the capture");
    std::vector<float> beatInput(SpectrumSamples * 2, 0.0f);
    std::vector<float> beatLeft, beatRight;
    std::copy(m_waveformL.begin(), m_waveformL.end(), beatInput.begin());
    m_beatFFT.TimeToFrequencyDomain(beatInput, beatLeft);
    std::copy(m_waveformR.begin(), m_waveformR.end(), beatInput.begin());
    m_beatFFT.TimeToFrequencyDomain(beatInput, beatRight);
    SpectrumBuffer monoSpectrum{};
    for (size_t sample = 0; sample < SpectrumSamples; sample++)
        monoSpectrum[sample] = 0.5f * (beatLeft[sample] + beatRight[sample]);]=]
    [=[    std::lock_guard<std::mutex> lock(m_pcmMutex);
    for (size_t i = 0; i < sampleCount; i++)]=]
    [=[    std::lock_guard<std::mutex> lock(m_pcmMutex);
    m_shaderAudioInput.BeginBlock();
    for (size_t i = 0; i < sampleCount; i++)]=]
    [=[            m_inputBufferR[bufferOffset] = m_inputBufferL[bufferOffset];
        }
    }]=]
    [=[            m_inputBufferR[bufferOffset] = m_inputBufferL[bufferOffset];
        }
        m_shaderAudioInput.Add(m_inputBufferL[bufferOffset], m_inputBufferR[bufferOffset]);
    }]=]
    [=[        CopyNewWaveformData(m_inputBufferR, m_waveformR);
    }]=]
    [=[        CopyNewWaveformData(m_inputBufferR, m_waveformR);
        m_shaderAudioInput.CopyForFrame();
    }
    m_shaderAudioInput.Analyze(secondsSinceLastFrame, frame);]=]
    [=[    FrameAudioData data{};]=]
    [=[    FrameAudioData data{};
    data.shaderAudio = m_shaderAudioInput.Frame();]=]
)

# All consumers must see the same PCM and FrameAudioData layouts, including
# ProjectM.hpp, Preset.hpp and user-sprite code. Preserve BeatAnalysis's aliases.
projectm_patched_path(Audio "${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/PCM.hpp" MILKDROP_PATCHED_PCM)
get_filename_component(MILKDROP_PATCHED_AUDIO_ROOT "${MILKDROP_PATCHED_PCM}" DIRECTORY)
foreach(HEADER IN ITEMS PCM FrameAudioData)
    configure_file("${MILKDROP_PATCHED_AUDIO_ROOT}/${HEADER}.hpp"
                   "${MILKDROP_PATCHED_AUDIO_ROOT}/Audio/${HEADER}.hpp" COPYONLY)
endforeach()
foreach(TARGET IN ITEMS Audio projectM_main MilkdropPreset Renderer UserSprites)
    target_include_directories(${TARGET} BEFORE PRIVATE
        "${MILKDROP_PATCHED_AUDIO_ROOT}" "${MILKDROP_SHADER_AUDIO_DIR}")
endforeach()

target_sources(Audio PRIVATE "${MILKDROP_SHADER_AUDIO_DIR}/ShaderAudioAnalysis.cpp")
target_sources(MilkdropPreset PRIVATE "${MILKDROP_SHADER_AUDIO_DIR}/ShaderAudioRenderer.cpp")
target_sources(projectM_main PRIVATE "${MILKDROP_SHADER_AUDIO_DIR}/ShaderAudioApi.cpp")
set_source_files_properties("${MILKDROP_SHADER_AUDIO_DIR}/ShaderAudioApi.cpp"
    TARGET_DIRECTORY projectM_main PROPERTIES COMPILE_DEFINITIONS projectM_api_EXPORTS)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetState.hpp
    [=[    BlendableFloat decay{0.98f};]=]
    [=[    BlendableFloat decay{0.98f};
    BlendableFloat fftAttack{0.5f};
    BlendableFloat fftDecay{0.7f};]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PresetState.cpp
    [=[    decay = parsedFile.GetFloat("fDecay", decay);]=]
    [=[    decay = parsedFile.GetFloat("fDecay", decay);
    fftAttack = parsedFile.GetFloat("FFTAttack", fftAttack);
    fftDecay = parsedFile.GetFloat("FFTDecay", fftDecay);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.hpp
    [=[#include "BlurTexture.hpp"]=]
    [=[#include "BlurTexture.hpp"
#include "ShaderAudioRenderer.hpp"]=]
    [=[    Renderer::Shader m_shader;]=]
    [=[    milkdrop::ShaderAudioTexture m_shaderAudio;
    Renderer::Shader m_shader;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());]=]
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());
    fullSource.append(milkdrop::ShaderAudioFunctions);]=]
    [=[    std::set<std::string> samplerDeclarations;]=]
    [=[    std::set<std::string> samplerDeclarations;
    samplerDeclarations.insert("uniform sampler2D _milkdrop_audio;\n");]=]
    [=[        desc.Bind(textureUnit, m_shader);
        textureUnit++;
    }
}

auto MilkdropShader::Shader()]=]
    [=[        desc.Bind(textureUnit, m_shader);
        textureUnit++;
    }
    m_shaderAudio.Bind(presetState.audioData.shaderAudio, m_shader, textureUnit,
                       presetState.fftAttack, presetState.fftDecay);
}

auto MilkdropShader::Shader()]=]
)
