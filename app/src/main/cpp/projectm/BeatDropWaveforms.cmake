# Match BeatDrop's stereo mix, angular sampling and viewport sample limits.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformMode.hpp
    [=[    Count                   //!< Always keep as last item!]=]
    [=[    BeatDropTriangle,
    BeatDropFireworks,
    Count                   //!< Always keep as last item!]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/WaveformMath.hpp
    [=[    virtual auto IsLoop() -> bool;]=]
    [=[    virtual auto IsLoop() -> bool;
    virtual float OpacityMultiplier() const { return 1.0f; }]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/Factory.cpp
    [=[#include "Waveforms/Factory.hpp"]=]
    [=[#include "Waveforms/Factory.hpp"
#include "BeatDropExtraWaveforms.hpp"]=]
    [=[        case WaveformMode::Count:]=]
    [=[        case WaveformMode::BeatDropTriangle:
            return std::make_unique<BeatDropExtraWaveform>(false);
        case WaveformMode::BeatDropFireworks:
            return std::make_unique<BeatDropExtraWaveform>(true);
        case WaveformMode::Count:]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveform.cpp
    [=[    int texsize = std::max(m_presetState.renderContext.viewportSizeX, m_presetState.renderContext.viewportSizeY);]=]
    [=[    int texsize = m_presetState.renderContext.viewportSizeX;
    if (m_mode == WaveformMode::CenteredSpiroVolume &&
        (texsize == 256 || texsize == 512 || texsize == 1024 || texsize == 2048)) m_tempAlpha = 1.0f;
    if (m_mode == WaveformMode::XYOscillationSpiral || m_mode == WaveformMode::Milkdrop2077WaveSkewed)
        m_tempAlpha *= 1.25f;]=]
    [=[texsize <= 256]=] [=[texsize == 256]=]
    [=[texsize <= 512]=] [=[texsize == 512]=]
    [=[texsize <= 1024]=] [=[texsize == 1024]=]
    [=[texsize <= 2048]=] [=[texsize == 2048]=]
    [=[        else
        {
            // For larger screens, e.g. 4K
            m_tempAlpha *= 0.15f;
        }]=] [=[]=]
    [=[        else
        {
            // For larger screens, e.g. 4K
            m_tempAlpha *= 0.44f;
        }]=] [=[]=]
    [=[    glVertexAttrib4f(1, waveR, waveG, waveB, m_tempAlpha);]=]
    [=[    glVertexAttrib4f(1, waveR, waveG, waveB, m_tempAlpha * m_waveformMath->OpacityMultiplier());]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/Circle.cpp
    [=[    m_wave1Vertices.resize(m_samples);]=] [=[    m_wave1Vertices.resize(m_samples + 1);]=]
    [=[static_cast<float>(m_samples)};]=] [=[static_cast<float>(m_samples - 1)};]=]
    [=[m_pcmDataR[i + sampleOffset]]=]
    [=[(m_pcmDataL[i + sampleOffset] + m_pcmDataR[i + sampleOffset]) * 0.5f]=]
    [=[m_pcmDataR[i + m_samples + sampleOffset]]=]
    [=[(m_pcmDataL[i + m_samples + sampleOffset] + m_pcmDataR[i + m_samples + sampleOffset]) * 0.5f]=]
    [=[    }
}

} // namespace Waveforms]=]
    [=[    }
    m_wave1Vertices[m_samples] = m_wave1Vertices[0];
}

} // namespace Waveforms]=]
)
foreach(WAVE_CLASS IN ITEMS DerivativeLine Line DoubleLine Milkdrop2077Wave9 Milkdrop2077WaveX Milkdrop2077Wave11)
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/${WAVE_CLASS}.cpp
        [=[        m_samples /= 3;]=]
        [=[        m_samples = std::max(1, static_cast<int>(presetState.renderContext.viewportSizeX / 3));]=]
        "#include \"Waveforms/${WAVE_CLASS}.hpp\""
        "#include \"Waveforms/${WAVE_CLASS}.hpp\"\n#include <algorithm>"
    )
endforeach()
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/DerivativeLine.cpp
    [=[m_pcmDataL[i + sampleOffset] * 0.47f]=]
    [=[(m_pcmDataL[i + sampleOffset] + m_pcmDataR[i + sampleOffset]) * 0.5f * 0.47f]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/Milkdrop2077Wave9.cpp
    [=[    m_wave2Vertices.resize(m_samples);]=] [=[    m_wave2Vertices.clear();]=]
    [=[0.35f * m_pcmDataL[i + m_sampleOffset]]=]
    [=[0.5f * (m_pcmDataL[i + m_sampleOffset] + m_pcmDataR[i + m_sampleOffset])]=]
)
foreach(WAVE_CLASS IN ITEMS Milkdrop2077WaveStar Milkdrop2077WaveFlower)
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/${WAVE_CLASS}.cpp
        [=[m_pcmDataR[sample + sampleOffset]]=]
        [=[(m_pcmDataL[sample + sampleOffset] + m_pcmDataR[sample + sampleOffset]) * 0.5f]=]
        [=[#include <cmath>]=] [=[#include <cmath>
#include <algorithm>]=]
        [=[            // Subtracting the sample offset here instead of adding it, as the original Milkdrop2077 code accessed out-of-range data.]=]
        [=[            // Match the forward sample window without reading beyond valid PCM.
            const int tail = std::min(sample + m_samples + sampleOffset, Audio::WaveformSamples - 1);]=]
        [=[m_pcmDataR[sample + m_samples - sampleOffset]]=]
        [=[(m_pcmDataL[tail] + m_pcmDataR[tail]) * 0.5f]=]
        [=[    }
}

} // namespace Waveforms]=]
        [=[    }
    m_wave1Vertices[m_samples] = m_wave1Vertices[0];
}

} // namespace Waveforms]=]
    )
endforeach()
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/Milkdrop2077WaveLasso.cpp
    [=[float const angle = m_pcmDataL[sample + 32] * 1.57f + presetState.renderContext.time * 2.0f;]=]
    [=[float angle = (m_pcmDataL[sample + 32] + m_pcmDataR[sample + 32]) * 0.5f * 1.57f + presetState.renderContext.time * 2.0f;
        if (fabsf(angle) < 0.001f) angle = angle < 0.0f ? -0.001f : 0.001f;]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveforms/Milkdrop2077WaveSkewed.cpp
    [=[        alpha *= presetState.audioData.vol;]=]
    [=[        const float start = presetState.modWaveAlphaStart;
        const float end = presetState.modWaveAlphaEnd;
        if (presetState.audioData.vol <= start) alpha = 0.0f;
        else if (presetState.audioData.vol < end)
            alpha *= (presetState.audioData.vol - start) / (end - start);]=]
)
