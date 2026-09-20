#version 460
#extension GL_EXT_ray_tracing : require
layout(location = 0) rayPayloadInEXT vec3 radiance;
void main() {
    // Visibility-debug miss color, not a claim of physical sky lighting.
    radiance = vec3(0.018, 0.025, 0.045);
}
