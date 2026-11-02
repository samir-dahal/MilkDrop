# Replace only projectM's band analysis, in build-directory copies. Capture APIs, audio
# modes, waveform data, and the spectrum exposed to presets are unchanged.
set(MILKDROP_ANALYSIS_SAMPLE_RATE 44100 CACHE STRING "Legacy band-analysis frequency reference in Hz (shader FFT uses capture metadata)")
set(MILKDROP_BASS_LOW_HZ 0 CACHE STRING "Lowest analyzed bass frequency")
set(MILKDROP_BASS_HIGH_HZ 200 CACHE STRING "Bass/mid crossover")
set(MILKDROP_MID_HIGH_HZ 4000 CACHE STRING "Mid/treble crossover")
set(MILKDROP_TREBLE_HIGH_HZ 20000 CACHE STRING "Highest analyzed treble frequency (clamped to Nyquist)")

foreach(OPTION IN ITEMS ANALYSIS_SAMPLE_RATE BASS_LOW_HZ BASS_HIGH_HZ MID_HIGH_HZ TREBLE_HIGH_HZ)
    if(NOT "${MILKDROP_${OPTION}}" MATCHES "^[0-9]+([.][0-9]+)?$")
        message(FATAL_ERROR "MILKDROP_${OPTION} must be a positive number")
    endif()
endforeach()

# BeatDrop's band sums use this legacy FFT weighting. Fail explicitly if master
# changes it instead of silently changing the beat-variable response.
file(READ ${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/MilkdropFFT.cpp FFT_SOURCE)
foreach(EXPECTED IN ITEMS "m_numFrequencies(samplesOut * 2)" "float const scaling = -0.02f;"
                          "scaling * std::log(static_cast<float>(m_numFrequencies / 2 - i) * inverseHalfNumFrequencies)")
    string(FIND "${FFT_SOURCE}" "${EXPECTED}" MATCH_POSITION)
    if(MATCH_POSITION EQUAL -1)
        message(FATAL_ERROR "projectM FFT changed; revisit BeatAnalysis.cmake: ${EXPECTED}")
    endif()
endforeach()
add_configure_dependency(${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/MilkdropFFT.cpp)
file(READ ${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/PCM.hpp PCM_HEADER)
string(FIND "${PCM_HEADER}" "m_fft{WaveformSamples, SpectrumSamples, true}" MATCH_POSITION)
if(MATCH_POSITION EQUAL -1)
    message(FATAL_ERROR "projectM PCM FFT configuration changed; revisit BeatAnalysis.cmake")
endif()
add_configure_dependency(${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/PCM.hpp)

foreach(EXT IN ITEMS hpp cpp)
    set(ORIGINAL ${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/Loudness.${EXT})
    set(REPLACEMENT ${CMAKE_CURRENT_LIST_DIR}/AdaptiveLoudness.${EXT})
    file(READ ${ORIGINAL} ORIGINAL_CONTENT)
    file(READ ${REPLACEMENT} REPLACEMENT_CONTENT)
    add_configure_dependency(${REPLACEMENT})
    patch_projectm_source(Audio src/libprojectM/Audio/Loudness.${EXT}
                         "${ORIGINAL_CONTENT}" "${REPLACEMENT_CONTENT}")
endforeach()

projectm_patched_path(Audio ${PROJECTM_SOURCE_DIR}/src/libprojectM/Audio/Loudness.hpp PATCHED_HEADER)
get_filename_component(PATCHED_AUDIO_ROOT ${PATCHED_HEADER} DIRECTORY)
# Audio is its own target, so its patched files have no Audio/ prefix. Provide that
# prefix as well for includes made from PCM.hpp and ProjectM.hpp.
configure_file(${CMAKE_CURRENT_LIST_DIR}/AdaptiveLoudness.hpp
               ${PATCHED_AUDIO_ROOT}/Audio/Loudness.hpp COPYONLY)
configure_file(${CMAKE_CURRENT_LIST_DIR}/BeatAnalysisConfig.hpp.in
               ${PATCHED_AUDIO_ROOT}/BeatAnalysisConfig.hpp @ONLY)
# PCM contains Loudness by value. Every consumer must see the same patched class layout;
# projectM_main otherwise puts its original libprojectM include directory first.
target_include_directories(Audio BEFORE PUBLIC ${PATCHED_AUDIO_ROOT})
target_include_directories(projectM_main BEFORE PRIVATE ${PATCHED_AUDIO_ROOT})
