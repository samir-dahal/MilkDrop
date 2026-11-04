# A separate switching clock preserves preset animation time and any blend in progress.
patch_projectm_source(projectM_main src/libprojectM/TimeKeeper.hpp
    [=[    void StartPreset();]=]
    [=[    void StartPreset();

    void RestartPresetCountdown();
    void SetCountdownEnabled(bool enabled);
    double SampledTransitionDuration();
    double ActiveSoftCutDuration() const { return m_activeSoftCutDuration; }
]=]
    [=[        m_presetDuration = seconds;]=]
    [=[        m_presetDuration = seconds;
        RestartPresetCountdown();]=]
    [=[        m_easterEgg = value;]=]
    [=[        m_easterEgg = value;
        RestartPresetCountdown();]=]
    [=[    double m_presetTimeA{};]=]
    [=[    double m_countdownTimeA{};
    double m_countdownTimeB{};
    bool m_countdownEnabled{true};
    double m_presetTimeA{};]=]
    [=[    double m_softCutDuration{};]=]
    [=[    double m_softCutDuration{};
    double m_activeSoftCutDuration{};]=]
)
patch_projectm_source(projectM_main src/libprojectM/TimeKeeper.cpp
    [=[    m_currentTime = currentFrameTime;]=]
    [=[    m_currentTime = currentFrameTime;
    if (!m_countdownEnabled)
    {
        m_countdownTimeA = m_currentTime;
        m_countdownTimeB = m_currentTime;
    }]=]
    [=[void TimeKeeper::StartPreset()]=]
    [=[double TimeKeeper::SampledTransitionDuration()
{
    if (m_softCutDuration >= 0.0) return m_softCutDuration;
    constexpr double choices[]{0, 0.5, 1.0, 1.5, 1.7, 2.0, 2.5, 3.0, 5.0};
    std::uniform_int_distribution<int> choose(0, sizeof(choices) / sizeof(choices[0]) - 1);
    return choices[choose(m_randomGenerator)];
}

void TimeKeeper::RestartPresetCountdown()
{
    m_countdownTimeA = m_currentTime;
    m_countdownTimeB = m_currentTime;
    m_presetDurationA = sampledPresetDuration();
    m_presetDurationB = sampledPresetDuration();
}

void TimeKeeper::SetCountdownEnabled(bool enabled)
{
    m_countdownEnabled = enabled;
    RestartPresetCountdown();
}

void TimeKeeper::StartPreset()]=]
    [=[    m_presetTimeA = m_currentTime;]=]
    [=[    m_presetTimeA = m_currentTime;
    m_countdownTimeA = m_currentTime;]=]
    [=[    m_presetTimeB = m_currentTime;]=]
    [=[    m_presetTimeB = m_currentTime;
    m_countdownTimeB = m_currentTime;
    m_activeSoftCutDuration = SampledTransitionDuration();]=]
    [=[    m_presetTimeA = m_presetTimeB;]=]
    [=[    m_presetTimeA = m_presetTimeB;
    m_countdownTimeA = m_countdownTimeB;]=]
    [=[return (m_currentTime - m_presetTimeA) > m_hardCutDuration;]=]
    [=[return m_countdownEnabled && (m_currentTime - m_countdownTimeA) > m_hardCutDuration;]=]
    [=[return (m_currentTime - m_presetTimeB) / m_softCutDuration;]=]
    [=[return m_activeSoftCutDuration <= 0.0 ? 1.0 : (m_currentTime - m_presetTimeB) / m_activeSoftCutDuration;]=]
    [=[double TimeKeeper::PresetProgressA()
{]=]
    [=[double TimeKeeper::PresetProgressA()
{
    if (!m_countdownEnabled) return 0.0;]=]
    [=[double TimeKeeper::PresetProgressB()
{]=]
    [=[double TimeKeeper::PresetProgressB()
{
    if (!m_countdownEnabled) return 0.0;]=]
    [=[(m_currentTime - m_presetTimeA) / m_presetDurationA]=]
    [=[(m_currentTime - m_countdownTimeA) / m_presetDurationA]=]
    [=[(m_currentTime - m_presetTimeB) / m_presetDurationB]=]
    [=[(m_currentTime - m_countdownTimeB) / m_presetDurationB]=]
    [=[    std::normal_distribution<double> gaussianDistribution(m_presetDuration, m_easterEgg);
    return std::max<double>(1.0, gaussianDistribution(m_randomGenerator));]=]
    [=[    // Android's "Additional random time" is a bounded addition, never a shorter duration.
    std::uniform_real_distribution<double> additionalTime(0.0, m_easterEgg);
    return m_presetDuration + additionalTime(m_randomGenerator);]=]
)
patch_projectm_copy(projectM_main src/libprojectM/ProjectM.cpp
    [=[m_transitionShaderManager->RandomTransition(), m_softCutDuration, m_timeKeeper->GetFrameTime()]=]
    [=[m_transitionShaderManager->RandomTransition(), m_timeKeeper->ActiveSoftCutDuration(), m_timeKeeper->GetFrameTime()]=]
    [=[    m_presetLocked = locked;]=]
    [=[    m_timeKeeper->SetCountdownEnabled(!locked);
    m_presetLocked = locked;]=]
    [=[    m_timeKeeper->ChangePresetDuration(seconds);]=]
    [=[    m_timeKeeper->ChangePresetDuration(seconds);
    m_presetChangeNotified = m_presetLocked;]=]
    [=[    m_timeKeeper->ChangeEasterEgg(value);]=]
    [=[    m_timeKeeper->ChangeEasterEgg(value);
    m_presetChangeNotified = m_presetLocked;]=]
    [=[    // ToDo: Add a preset switch timer separate from the display timer and reset to 0 when
    //       disabling the preset switch lock.]=]
    [=[    // Reset the independent switching clock, preserving animation and blend timing.]=]
)
