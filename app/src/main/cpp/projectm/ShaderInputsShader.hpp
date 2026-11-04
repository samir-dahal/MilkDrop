#pragma once

namespace milkdrop {

inline constexpr const char ShaderInputsHeader[] = R"hlsl(
uniform float4 _milkdrop_pointer;
uniform float4 _milkdrop_sys_time;
uniform float4 _milkdrop_sys_date;
// A writable copy preserves other components when presets assign mouse.z or mouse.w.
static float4 _milkdrop_mouse = float4(-1.0, -1.0, 0.0, 0.0);
#define mouse _milkdrop_mouse
#define mouse_x _milkdrop_mouse.x
#define mouse_y _milkdrop_mouse.y
#define mouse_pos _milkdrop_mouse.xy
#define mouse_clicked _milkdrop_mouse.z
#define mouse_pressed _milkdrop_mouse.w
// Historical alias retained for existing presets.
#define mouse_released _milkdrop_mouse.w
#define sysTime _milkdrop_sys_time
#define sysDate _milkdrop_sys_date
#define sysTimeHour _milkdrop_sys_time.x
#define sysTimeMinute _milkdrop_sys_time.y
#define sysTimeSecond _milkdrop_sys_time.z
#define sysTimeTotalSeconds _milkdrop_sys_time.w
#define sysDateYear _milkdrop_sys_date.x
#define sysDateMonth _milkdrop_sys_date.y
#define sysDateDay _milkdrop_sys_date.z
#define sysDateWeekday _milkdrop_sys_date.w
)hlsl";

} // namespace milkdrop
