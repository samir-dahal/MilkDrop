#pragma once

#include <projectM-4/projectM.h>

// Coordinates are normalized to the visualizer: (0, 0) is the top left.
// Use (-1, -1) when the pointer is outside. A release is visible for one frame.
extern "C" PROJECTM_EXPORT void milkdrop_set_pointer(projectm_handle instance,
                                                    float x, float y,
                                                    bool down, bool released);
