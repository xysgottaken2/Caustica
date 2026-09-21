#version 460
#extension GL_EXT_ray_tracing : require
layout(location = 0) rayPayloadInEXT vec3 radiance;
hitAttributeEXT vec2 barycentric;
void main() {
    // True hit barycentrics and instance ID visualize traversal of the BLAS/TLAS.
    vec3 bary = vec3(1.0 - barycentric.x - barycentric.y, barycentric.x, barycentric.y);
    float tint = 0.5 + 0.5 * fract(float(gl_InstanceCustomIndexEXT) * 0.61803398875);
    radiance = (vec3(0.12) + 0.88 * bary) * tint;
}
