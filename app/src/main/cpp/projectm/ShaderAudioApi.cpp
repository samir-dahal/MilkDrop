#include "ShaderAudioApi.hpp"

#include <ProjectMCWrapper.hpp>

extern "C" PROJECTM_EXPORT void milkdrop_set_shader_audio_settings(projectm_handle instance, float scaling, float noiseFloor)
{
    if (instance)
        reinterpret_cast<libprojectM::projectMWrapper*>(instance)->PCM().SetShaderAudioSettings(scaling, noiseFloor);
}

extern "C" PROJECTM_EXPORT void milkdrop_set_audio_input_format(projectm_handle instance, uint32_t sampleRate, bool snapshot)
{
    if (instance)
        reinterpret_cast<libprojectM::projectMWrapper*>(instance)->PCM().SetShaderAudioInputFormat(sampleRate, snapshot);
}
