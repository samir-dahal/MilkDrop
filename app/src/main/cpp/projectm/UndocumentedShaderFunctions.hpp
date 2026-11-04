#pragma once
#include <string>
#include <regex>
namespace milkdrop {
inline std::string UndocumentedShaderFunctions(std::string source) {
const bool needNoiseOrFinite = std::regex_search(source, std::regex(R"(\b(?:noise|isfinite)\s*\()"));
if (!std::regex_search(source, std::regex(R"(\b(?:float[1-4]?|bool[1-4]?)\s+modf\s*\()")))
source=std::regex_replace(source,std::regex(R"(\bmodf\s*\()"),"_md_modf(");
if (!std::regex_search(source, std::regex(R"(\b(?:float[1-4]?|bool[1-4]?)\s+lit\s*\()")))
source=std::regex_replace(source,std::regex(R"(\blit\s*\()"),"_md_lit(");
if (!std::regex_search(source, std::regex(R"(\b(?:float[1-4]?|bool[1-4]?)\s+isfinite\s*\()")))
source=std::regex_replace(source,std::regex(R"(\bisfinite\s*\()"),"_md_isfinite(");
if (!std::regex_search(source, std::regex(R"(\b(?:float[1-4]?|bool[1-4]?)\s+noise\s*\()")))
source=std::regex_replace(source,std::regex(R"(\bnoise\s*\()"),"_md_noise(");
source=std::regex_replace(source,std::regex(R"(\btex1D\s*\()"),"_md_tex1D(");
source=std::regex_replace(source,std::regex(R"(\btex1Dgrad\s*\()"),"_md_tex1Dgrad(");
source=std::regex_replace(source,std::regex(R"(\btex1Dlod\s*\()"),"_md_tex1Dlod(");
source=std::regex_replace(source,std::regex(R"(\btex1Dbias\s*\()"),"_md_tex1Dbias(");
source=std::regex_replace(source,std::regex(R"(\btex1Dproj\s*\()"),"_md_tex1Dproj(");
source=std::regex_replace(source,std::regex(R"(\btex2D\s*\()"),"_md_tex2D(");
source=std::regex_replace(source,std::regex(R"(\btex3D\s*\()"),"_md_tex3D(");
source=std::regex_replace(source,std::regex(R"(\btex3Dproj\s*\()"),"_md_tex3Dproj(");
source=std::regex_replace(source,std::regex(R"(\btexCUBE\s*\()"),"_md_texCUBE(");
source=std::regex_replace(source,std::regex(R"(\btexCUBEproj\s*\()"),"_md_texCUBEproj(");
source=std::regex_replace(source,std::regex(R"(\bsampler1D\b)"),"sampler2D");
return std::string(needNoiseOrFinite ? "#define _MD_NEED_NOISE_OR_FINITE\n" : "") + std::string(R"MD(
float _md_modf(float x,out float ip) {ip=trunc(x);return x-ip;}
float2 _md_modf(float2 x,out float2 ip) {ip=trunc(x);return x-ip;}
float3 _md_modf(float3 x,out float3 ip) {ip=trunc(x);return x-ip;}
float4 _md_modf(float4 x,out float4 ip) {ip=trunc(x);return x-ip;}
float4 _md_lit(float nl,float nh,float m) { return float4(1,max(nl,0),nl>0 && nh>0 ? pow(nh,clamp(m,-128,128)) : 0,1); }
float4 _md_tex2D(sampler2D s,float2 t) {return tex2D(s,t);}
float4 _md_tex2D(sampler2D s,float2 t,float2 dx,float2 dy) {return tex2Dgrad(s,t,dx,dy);}
float4 _md_tex1D(sampler2D s,float u) {return tex2D(s,float2(u,.5));}
float4 _md_tex1D(sampler2D s,float u,float dx,float dy) {return tex2Dgrad(s,float2(u,.5),float2(dx,0),float2(dy,0));}
float4 _md_tex1Dgrad(sampler2D s,float u,float dx,float dy) {return tex2Dgrad(s,float2(u,.5),float2(dx,0),float2(dy,0));}
float4 _md_tex1Dlod(sampler2D s,float4 t) {return tex2Dlod(s,float4(t.x,.5,0,t.w));}
float4 _md_tex1Dbias(sampler2D s,float4 t) {return tex2Dbias(s,float4(t.x,.5,0,t.w));}
float4 _md_tex1Dproj(sampler2D s,float4 t) {return tex2D(s,float2(t.x/t.w,.5));}
float4 _md_tex3D(sampler3D s,float3 t) {return tex3D(s,t);}
float4 _md_tex3D(sampler3D s,float3 t,float3 dx,float3 dy) {return tex3Dgrad(s,t,dx,dy);}
float4 _md_tex3Dproj(sampler3D s,float4 t) {return tex3D(s,t.xyz/t.w);}
float4 _md_texCUBE(samplerCUBE s,float3 t) {return texCUBE(s,t);}
float4 _md_texCUBE(samplerCUBE s,float3 t,float3 dx,float3 dy) {return texCUBEgrad(s,t,dx,dy);}
float4 _md_texCUBEproj(samplerCUBE s,float4 t) {return texCUBE(s,t.xyz/t.w);}
#ifdef _MD_NEED_NOISE_OR_FINITE
float _md_noise_hash(float p) {return frac(sin(p)*43758.5453)*2-1;}
bool _md_isfinite(float x) {return !isnan(x) && !isinf(x);}
float _md_noise(float x) { float b=floor(x); float f=frac(x); float u=f*f*f*(f*(f*6-15)+10); float v=0;
{float h=(b+0)*127.1; v+=(_md_noise_hash(h+0.0)*(f-0))*(1-u);}
{float h=(b+1)*127.1; v+=(_md_noise_hash(h+0.0)*(f-1))*u;}
return clamp(v,-1,1);}
bool2 _md_isfinite(float2 x) {return bool2(_md_isfinite(x.x),_md_isfinite(x.y));}
float _md_noise(float2 x) { float2 b=floor(x); float2 f=frac(x); float2 u=f*f*f*(f*(f*6-15)+10); float v=0;
{float h=(b.x+0)*127.1+(b.y+0)*311.7; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0))*(1-u.x)*(1-u.y);}
{float h=(b.x+1)*127.1+(b.y+0)*311.7; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0))*u.x*(1-u.y);}
{float h=(b.x+0)*127.1+(b.y+1)*311.7; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1))*(1-u.x)*u.y;}
{float h=(b.x+1)*127.1+(b.y+1)*311.7; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1))*u.x*u.y;}
return clamp(v,-1,1);}
bool3 _md_isfinite(float3 x) {return bool3(_md_isfinite(x.x),_md_isfinite(x.y),_md_isfinite(x.z));}
float _md_noise(float3 x) { float3 b=floor(x); float3 f=frac(x); float3 u=f*f*f*(f*(f*6-15)+10); float v=0;
{float h=(b.x+0)*127.1+(b.y+0)*311.7+(b.z+0)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-0))*(1-u.x)*(1-u.y)*(1-u.z);}
{float h=(b.x+1)*127.1+(b.y+0)*311.7+(b.z+0)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-0))*u.x*(1-u.y)*(1-u.z);}
{float h=(b.x+0)*127.1+(b.y+1)*311.7+(b.z+0)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-0))*(1-u.x)*u.y*(1-u.z);}
{float h=(b.x+1)*127.1+(b.y+1)*311.7+(b.z+0)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-0))*u.x*u.y*(1-u.z);}
{float h=(b.x+0)*127.1+(b.y+0)*311.7+(b.z+1)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-1))*(1-u.x)*(1-u.y)*u.z;}
{float h=(b.x+1)*127.1+(b.y+0)*311.7+(b.z+1)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-1))*u.x*(1-u.y)*u.z;}
{float h=(b.x+0)*127.1+(b.y+1)*311.7+(b.z+1)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-1))*(1-u.x)*u.y*u.z;}
{float h=(b.x+1)*127.1+(b.y+1)*311.7+(b.z+1)*74.7; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-1))*u.x*u.y*u.z;}
return clamp(v,-1,1);}
bool4 _md_isfinite(float4 x) {return bool4(_md_isfinite(x.x),_md_isfinite(x.y),_md_isfinite(x.z),_md_isfinite(x.w));}
float _md_noise(float4 x) { float4 b=floor(x); float4 f=frac(x); float4 u=f*f*f*(f*(f*6-15)+10); float v=0;
{float h=(b.x+0)*127.1+(b.y+0)*311.7+(b.z+0)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*(1-u.x)*(1-u.y)*(1-u.z)*(1-u.w);}
{float h=(b.x+1)*127.1+(b.y+0)*311.7+(b.z+0)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*u.x*(1-u.y)*(1-u.z)*(1-u.w);}
{float h=(b.x+0)*127.1+(b.y+1)*311.7+(b.z+0)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*(1-u.x)*u.y*(1-u.z)*(1-u.w);}
{float h=(b.x+1)*127.1+(b.y+1)*311.7+(b.z+0)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*u.x*u.y*(1-u.z)*(1-u.w);}
{float h=(b.x+0)*127.1+(b.y+0)*311.7+(b.z+1)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*(1-u.x)*(1-u.y)*u.z*(1-u.w);}
{float h=(b.x+1)*127.1+(b.y+0)*311.7+(b.z+1)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*u.x*(1-u.y)*u.z*(1-u.w);}
{float h=(b.x+0)*127.1+(b.y+1)*311.7+(b.z+1)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*(1-u.x)*u.y*u.z*(1-u.w);}
{float h=(b.x+1)*127.1+(b.y+1)*311.7+(b.z+1)*74.7+(b.w+0)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-0))*u.x*u.y*u.z*(1-u.w);}
{float h=(b.x+0)*127.1+(b.y+0)*311.7+(b.z+0)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*(1-u.x)*(1-u.y)*(1-u.z)*u.w;}
{float h=(b.x+1)*127.1+(b.y+0)*311.7+(b.z+0)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*u.x*(1-u.y)*(1-u.z)*u.w;}
{float h=(b.x+0)*127.1+(b.y+1)*311.7+(b.z+0)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*(1-u.x)*u.y*(1-u.z)*u.w;}
{float h=(b.x+1)*127.1+(b.y+1)*311.7+(b.z+0)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-0)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*u.x*u.y*(1-u.z)*u.w;}
{float h=(b.x+0)*127.1+(b.y+0)*311.7+(b.z+1)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*(1-u.x)*(1-u.y)*u.z*u.w;}
{float h=(b.x+1)*127.1+(b.y+0)*311.7+(b.z+1)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-0)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*u.x*(1-u.y)*u.z*u.w;}
{float h=(b.x+0)*127.1+(b.y+1)*311.7+(b.z+1)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-0)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*(1-u.x)*u.y*u.z*u.w;}
{float h=(b.x+1)*127.1+(b.y+1)*311.7+(b.z+1)*74.7+(b.w+1)*269.5; v+=(_md_noise_hash(h+0.0)*(f.x-1)+_md_noise_hash(h+19.19)*(f.y-1)+_md_noise_hash(h+38.38)*(f.z-1)+_md_noise_hash(h+57.57000000000001)*(f.w-1))*u.x*u.y*u.z*u.w;}
return clamp(v,-1,1);}
#endif
)MD")+source;
}
}
