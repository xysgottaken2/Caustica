#version 460
#extension GL_EXT_ray_tracing : require
struct ShadowPayload { uint enabled; uint blocked; uint discarded; uint translucentSkipped; };
layout(location=1) rayPayloadInEXT ShadowPayload shadow;
void main() {
    shadow.blocked=0u;
}
