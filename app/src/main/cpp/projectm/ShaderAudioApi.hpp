#pragma once

#include <projectM-4/types.h>

extern "C" {

PROJECTM_EXPORT void milkdrop_set_shader_audio_settings(projectm_handle instance, float scaling, float noiseFloor);
PROJECTM_EXPORT void milkdrop_set_audio_input_format(projectm_handle instance, uint32_t sampleRate, bool snapshot);

}
