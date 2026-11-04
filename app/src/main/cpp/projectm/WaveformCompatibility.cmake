# Read the evaluated settings on every draw, and rebuild the math implementation
# only when the selected mode changes. Keep ownership in the preset's unique_ptr.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/Waveform.cpp
    [=[    m_mode = static_cast<WaveformMode>(m_presetState.waveMode % static_cast<int>(WaveformMode::Count));

    if (!m_waveformMath)
    {
        m_waveformMath = Waveforms::Factory::Create(m_mode);]=]
    [=[    const int count = static_cast<int>(WaveformMode::Count);
    const auto value = *presetPerFrameContext.wave_mode;
    const int mode = std::isfinite(value) ? static_cast<int>(std::fmod(value, count)) : 0;
    const auto selectedMode = static_cast<WaveformMode>((mode + count) % count);

    if (!m_waveformMath || selectedMode != m_mode)
    {
        m_mode = selectedMode;
        m_waveformMath = Waveforms::Factory::Create(m_mode);]=]
    [=[if (m_presetState.additiveWaves)]=] [=[if (*presetPerFrameContext.wave_additive != 0.0)]=]
    [=[m_presetState.waveThick || m_presetState.waveDots]=]
    [=[*presetPerFrameContext.wave_thick != 0.0 || *presetPerFrameContext.wave_usedots != 0.0]=]
    [=[m_waveMesh.SetRenderPrimitiveType(m_presetState.waveDots]=]
    [=[m_waveMesh.SetRenderPrimitiveType(*presetPerFrameContext.wave_usedots != 0.0]=]
)

# Dots represent discrete points, including iterated-function fractals. Adding
# interpolation vertices fills the gaps with fuzz even when rand/int are correct.
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/CustomWaveform.cpp
    [=[    float scaling{1.0f};]=]
    [=[    float scaling{1.0f};
    int leftStart = 0;
    int rightStart = 0;]=]
    [=[    else if (sampleCount > maxInputSampleCount)
    {
        // In oscilloscope mode, we ignore "separation", but use the scaling factor
        // to eventually scale up the 480 waveform samples to a larger, requested amount.
        scaling = static_cast<float>(maxInputSampleCount) / static_cast<float>(sampleCount);
    }]=]
    [=[    else
    {
        // Restore MilkDrop's centered, separated channel windows. When the
        // requested output exceeds available PCM, resample rather than read
        // beyond either end of the input buffer.
        const int gap = std::min(separation, maxInputSampleCount - 1);
        const int span = std::min(sampleCount, maxInputSampleCount - gap);
        leftStart = (maxInputSampleCount - span - gap) / 2;
        rightStart = leftStart + gap;
        scaling = static_cast<float>(span) / static_cast<float>(sampleCount);
    }]=]
    [=[sampleDataL[0] = pcmL[0];]=] [=[sampleDataL[0] = pcmL[leftStart];]=]
    [=[sampleDataR[0] = pcmR[0];]=] [=[sampleDataR[0] = pcmR[rightStart];]=]
    [=[sampleDataL[sample] = pcmL[pcmSample] * mix2]=]
    [=[sampleDataL[sample] = pcmL[pcmSample + leftStart] * mix2]=]
    [=[sampleDataR[sample] = pcmR[pcmSample] * mix2]=]
    [=[sampleDataR[sample] = pcmR[pcmSample + rightStart] * mix2]=]
    [=[    SmoothWave(points, colors);]=]
    [=[    if (m_useDots)
    {
        for (int sample = 0; sample < sampleCount; ++sample)
        {
            m_mesh.Vertices()[sample] = points[sample];
            m_mesh.Colors()[sample] = colors[sample];
        }
        m_mesh.Indices().Resize(sampleCount);
        m_mesh.Indices().MakeContinuous();
    }
    else
    {
        SmoothWave(points, colors);
    }]=]
    [=[if ((m_useDots && sampleCount < 1) || sampleCount < 2)]=]
    [=[if (sampleCount < (m_useDots ? 1 : 2))]=]
    [=[static_cast<float>(m_presetState.renderContext.viewportSizeX);

    size_t smoothedVertexCount]=]
    [=[static_cast<float>(m_presetState.renderContext.viewportSizeY);

    size_t smoothedVertexCount]=]
)
