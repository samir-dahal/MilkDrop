# Additional MilkDrop shader functions

The renderer accepts the functions listed in the supplied Undocumented Shader
Functions reference. Presets can use the existing HLSL intrinsics and these extensions
in warp and composite shaders:

- Scalar/vector `all`, `any`, `frexp` and `isfinite`.
- `degrees`, `radians`, `determinant`, `lit`, `sinh`, `tanh` and `trunc`.
- Scalar through four-dimensional continuous gradient `noise`, using quintic
  interpolation. Its gradient seed is specific to this renderer; matching values
  across unrelated DirectX drivers is not guaranteed.
- Correct vector results for `fwidth`, `isinf` and `isnan`, and scalar/vector
  `modf` output parameters with signed fractional values.
- `round` uses ties-to-even rounding, including negative halfway cases.
- Floating-point `%` and `fmod` preserve the dividend's sign, following HLSL's
  truncation-based remainder rather than GLSL's floor-based `mod`.
- `tex1D`, `tex1Dgrad`, `tex1Dbias`, `tex1Dlod` and `tex1Dproj` use a 2D image
  sampled at vertical coordinate 0.5. GLES has no native 1D texture target.
- Gradient overloads of `tex2D`, `tex3D` and `texCUBE`; explicit gradient and
  projection variants; 3D bias and explicit mip-level sampling.

Texture functions require the matching sampler and texture target. Cube sampling
syntax is supported, but ordinary JPG/PNG images loaded by the current texture
manager are 2D textures, not cube maps. This change does not add a cube-map asset
loader. The built-in volume noise textures provide 3D sampling inputs.

External textures belong in `MilkDropApp/textures/`; `.jfif` is supported alongside
formats such as JPG and PNG. Random samplers accept `sampler_rand00` or `rand00` in
`tex2D` calls. A suffix such as `rand00_Stu` filters candidates by filename prefix,
ignoring case. The same random slot retains its chosen image between warp and composite
shaders for that preset.

## Preprocessor behavior

Feature guards support nested `#if`, `#ifdef`, `#ifndef` and `#else` branches.
`HAS_FFT_PEAK` is defined as `1`. Empty defines and whitespace after `#` are supported.
Function-like macros preserve the author's grouping and may expand to multiple
statements. Put parentheses around macro arguments and expressions when grouping is
required; the translator does not add them automatically.

For audio functions such as `get_fft()` and `get_wave_left()`, clock variables,
pointer input and `q1`–`q64`, see [shader inputs](SHADER_INPUTS.md).

Explicit LOD and bias use the mip levels actually present in the source texture.
Derivative functions and gradient-based sampling run in fragment shaders; results
inside divergent branches can vary by GPU, as with DirectX shaders.

Compatibility checks use temporary native shader fixtures outside the tracked sources.
Successful compilation alone does not establish identical rendering across DirectX and
OpenGL ES: input audio, textures, animation time, render dimensions and GPU behavior
also affect the result. No test presets are packaged with the app.
