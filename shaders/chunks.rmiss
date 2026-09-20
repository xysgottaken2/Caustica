#version 460
#extension GL_EXT_ray_tracing : require
struct Hit { vec3 color; uint found; ivec3 section; uint primitive; vec2 uv; uint sampled; ivec2 atlasSize; vec2 quadSpan; uint mode; int levels; vec4 baseTint; vec4 overlayTint; vec4 sampleColor; vec2 baseUv; uint overlayState; uint face; uint layer; uvec4 alphaStats; vec4 cutoutSample; ivec4 cutoutSection; float distance; float alpha; uvec2 previous; uvec4 transStats; vec4 transSample; ivec4 transSection; uint entityRecord; };
layout(location=0) rayPayloadInEXT Hit hit;
void main() { hit.entityRecord=0xffffffffu; hit.color=vec3(0.018,0.025,0.045); hit.found=0u; hit.sampled=0u; }
