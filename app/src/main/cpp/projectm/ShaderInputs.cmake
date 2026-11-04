# Preserve all earlier source patches and keep input ownership inside each instance.
target_include_directories(projectM_main PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_include_directories(MilkdropPreset PRIVATE ${CMAKE_CURRENT_LIST_DIR})

patch_projectm_source(Renderer src/libprojectM/Renderer/RenderContext.hpp
    [=[#include <projectM-4/projectM_cxx_export.h>]=]
    [=[#include <projectM-4/projectM_cxx_export.h>
#include "ShaderInputsState.hpp"]=]
    [=[    float time{0.0f};]=]
    [=[    milkdrop::ShaderInputValues shaderInputs;
    float time{0.0f};]=]
)
projectm_patched_path(Renderer ${PROJECTM_SOURCE_DIR}/src/libprojectM/Renderer/RenderContext.hpp INPUT_CONTEXT_HEADER)
get_filename_component(INPUT_CONTEXT_ROOT ${INPUT_CONTEXT_HEADER} DIRECTORY)
file(MAKE_DIRECTORY ${INPUT_CONTEXT_ROOT}/Renderer)
configure_file(${INPUT_CONTEXT_HEADER} ${INPUT_CONTEXT_ROOT}/Renderer/RenderContext.hpp COPYONLY)
# Every object target sees the same RenderContext layout, including user sprites.
foreach(INPUT_TARGET IN ITEMS projectM_main MilkdropPreset Renderer UserSprites)
    target_include_directories(${INPUT_TARGET} BEFORE PRIVATE ${INPUT_CONTEXT_ROOT})
    target_include_directories(${INPUT_TARGET} PRIVATE ${CMAKE_CURRENT_LIST_DIR})
endforeach()

patch_projectm_source(projectM_main src/libprojectM/ProjectM.hpp
    [=[#include <cstdint>]=]
    [=[#include <cstdint>
#include "ShaderInputsState.hpp"]=]
    [=[    ProjectM();]=]
    [=[    ProjectM();

    void SetShaderPointer(float x, float y, bool down, bool released)
    {
        m_shaderInputs.SetPointer(x, y, down, released);
    }]=]
    [=[private:]=]
    [=[private:
    milkdrop::ShaderInputsState m_shaderInputs;]=]
)
patch_projectm_copy(projectM_main src/libprojectM/ProjectM.cpp
    [=[    // Update FPS and other timer values.]=]
    [=[    m_shaderInputs.BeginFrame();

    // Update FPS and other timer values.]=]
    [=[    Renderer::RenderContext ctx{};]=]
    [=[    Renderer::RenderContext ctx{};
    ctx.shaderInputs = m_shaderInputs.Snapshot();]=]
)
# The C wrapper derives from ProjectM; copy both files beside the patched header
# so their quoted includes use the same instance layout.
patch_projectm_source(projectM_main src/libprojectM/ProjectMCWrapper.hpp
    [=[#include "ProjectM.hpp"]=] [=[#include "ProjectM.hpp"]=])
patch_projectm_source(projectM_main src/libprojectM/ProjectMCWrapper.cpp
    [=[#include "ProjectMCWrapper.hpp"]=] [=[#include "ProjectMCWrapper.hpp"]=])
projectm_patched_path(projectM_main ${PROJECTM_SOURCE_DIR}/src/libprojectM/ProjectM.hpp INPUT_MAIN_HEADER)
get_filename_component(INPUT_MAIN_ROOT ${INPUT_MAIN_HEADER} DIRECTORY)
target_include_directories(projectM_main BEFORE PRIVATE ${INPUT_MAIN_ROOT})
projectm_patched_path(projectM_main ${PROJECTM_SOURCE_DIR}/src/libprojectM/ProjectMCWrapper.cpp INPUT_WRAPPER_CPP)
set_source_files_properties(${INPUT_WRAPPER_CPP} TARGET_DIRECTORY projectM_main PROPERTIES COMPILE_DEFINITIONS projectM_api_EXPORTS)
target_sources(projectM_main PRIVATE ${CMAKE_CURRENT_LIST_DIR}/ShaderInputs.cpp)
set_source_files_properties(${CMAKE_CURRENT_LIST_DIR}/ShaderInputs.cpp TARGET_DIRECTORY projectM_main PROPERTIES COMPILE_DEFINITIONS projectM_api_EXPORTS)

foreach(INPUT_CLASS IN ITEMS PerFrameContext PerPixelContext WaveformPerFrameContext WaveformPerPointContext ShapePerFrameContext)
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/${INPUT_CLASS}.hpp
        [=[#pragma once]=]
        [=[#pragma once
#include "ShaderInputsEval.hpp"]=]
        "class ${INPUT_CLASS}\n{\npublic:"
        "class ${INPUT_CLASS}\n{\npublic:\n    milkdrop::ShaderInputsEval shaderInputs;"
    )
    if(INPUT_CLASS STREQUAL "PerPixelContext")
        set(INPUT_VM perPixelCodeContext)
    elseif(INPUT_CLASS STREQUAL "WaveformPerPointContext")
        set(INPUT_VM perPointCodeContext)
    else()
        set(INPUT_VM perFrameCodeContext)
    endif()
    patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/${INPUT_CLASS}.cpp
        "    projectm_eval_context_reset_variables(${INPUT_VM});"
        "    projectm_eval_context_reset_variables(${INPUT_VM});\n    shaderInputs.Register(${INPUT_VM});"
    )
endforeach()

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerFrameContext.cpp
    [=[void PerFrameContext::LoadStateVariables(PresetState& state)
{]=]
    [=[void PerFrameContext::LoadStateVariables(PresetState& state)
{
    shaderInputs.Load(state.renderContext.shaderInputs);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/PerPixelContext.cpp
    [=[void PerPixelContext::LoadStateReadOnlyVariables(PresetState& state, PerFrameContext& perFrameState)
{]=]
    [=[void PerPixelContext::LoadStateReadOnlyVariables(PresetState& state, PerFrameContext& perFrameState)
{
    shaderInputs.Copy(perFrameState.shaderInputs);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformPerFrameContext.cpp
    [=[void WaveformPerFrameContext::LoadStateVariables(PresetState& state, const PerFrameContext& presetPerFrameContext, CustomWaveform& waveform)
{]=]
    [=[void WaveformPerFrameContext::LoadStateVariables(PresetState& state, const PerFrameContext& presetPerFrameContext, CustomWaveform& waveform)
{
    shaderInputs.Load(state.renderContext.shaderInputs);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/WaveformPerPointContext.cpp
    [=[void WaveformPerPointContext::LoadReadOnlyStateVariables(const PerFrameContext& presetPerFrameContext)
{]=]
    [=[void WaveformPerPointContext::LoadReadOnlyStateVariables(const PerFrameContext& presetPerFrameContext)
{
    shaderInputs.Copy(presetPerFrameContext.shaderInputs);]=]
)
patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/ShapePerFrameContext.cpp
    [=[    *time = static_cast<double>(state.renderContext.time);]=]
    [=[    shaderInputs.Load(state.renderContext.shaderInputs);
    *time = static_cast<double>(state.renderContext.time);]=]
)

patch_projectm_copy(MilkdropPreset src/libprojectM/MilkdropPreset/MilkdropShader.cpp
    [=[#include "MilkdropShader.hpp"]=]
    [=[#include "MilkdropShader.hpp"
#include "ShaderInputsShader.hpp"]=]
    [=[    m_shader.Bind();]=]
    [=[    m_shader.Bind();
    const auto& input = presetState.renderContext.shaderInputs;
    m_shader.SetUniformFloat4("_milkdrop_pointer", {input.mouseX, input.mouseY,
        input.down ? input.clickX : -input.clickX,
        input.pressed ? input.clickY : -input.clickY});
    m_shader.SetUniformFloat4("_milkdrop_sys_time", {input.hour, input.minute,
        input.second, static_cast<float>(input.totalSeconds)});
    m_shader.SetUniformFloat4("_milkdrop_sys_date", {input.year, input.month,
        input.day, input.weekday});]=]
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());]=]
    [=[    fullSource.append(MilkdropStaticShaders::Get()->GetPresetShaderHeader());
    fullSource.append(milkdrop::ShaderInputsHeader);]=]
    [=[        std::string progMain = "{\nfloat3 ret = 0;\n";]=]
    [=[        std::string progMain = "{\n_milkdrop_mouse = _milkdrop_pointer;\nfloat3 ret = 0;\n";]=]
)
