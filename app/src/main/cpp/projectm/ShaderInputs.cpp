#include "ShaderInputs.hpp"

#include "ProjectMCWrapper.hpp"

extern "C" PROJECTM_EXPORT void milkdrop_set_pointer(projectm_handle instance,
                                                    float x, float y,
                                                    bool down, bool released)
{
    if (instance)
    {
        reinterpret_cast<libprojectM::projectMWrapper*>(instance)->SetShaderPointer(x, y, down, released);
    }
}
