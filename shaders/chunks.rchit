#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_buffer_reference_uvec2 : require

struct Hit { vec3 color; uint found; ivec3 section; uint primitive; vec2 uv; uint sampled; };
layout(location=0) rayPayloadInEXT Hit hit;
hitAttributeEXT vec2 barycentric;
struct Material { uvec4 addressLayout; uvec4 attributes; ivec4 section; };
layout(set=0,binding=2,std430) readonly buffer Materials { Material rows[]; } materials;
layout(set=0,binding=3) uniform sampler2D blockAtlas;
layout(buffer_reference,std430,buffer_reference_align=4) readonly buffer Vertices { uint words[]; };

vec2 uvAt(Vertices vertices, Material m, uint index) {
    uint offset=index*m.addressLayout.z+m.addressLayout.w;
    return vec2(uintBitsToFloat(vertices.words[offset]),uintBitsToFloat(vertices.words[offset+1u]));
}
vec4 colorAt(Vertices vertices, Material m, uint index) {
    return unpackUnorm4x8(vertices.words[index*m.addressLayout.z+m.attributes.x]);
}
void main() {
    // gl_InstanceID is the TLAS input row, independent of unchanged customIndex/SBT offsets.
    hit.found=1u; hit.sampled=0u; hit.primitive=uint(gl_PrimitiveID);
    hit.color=vec3(1,0,1); // Invalid metadata must be visibly different from a successful atlas sample.
    if (gl_InstanceID >= materials.rows.length()) return;
    Material m=materials.rows[gl_InstanceID];
    hit.section=m.section.xyz;
    if (uint(gl_PrimitiveID)>=m.attributes.z) return;
    // Exactly the implicit QUADS indices copied into the BLAS: 0,1,2, 2,3,0.
    uint base=(uint(gl_PrimitiveID)/2u)*4u;
    uvec3 indices=base+((uint(gl_PrimitiveID)&1u)==0u ? uvec3(0,1,2) : uvec3(2,3,0));
    if (any(greaterThanEqual(indices,uvec3(m.attributes.y)))) return;
    Vertices vertices=Vertices(m.addressLayout.xy);
    vec3 w=vec3(1.0-barycentric.x-barycentric.y,barycentric);
    hit.uv=uvAt(vertices,m,indices.x)*w.x+uvAt(vertices,m,indices.y)*w.y+uvAt(vertices,m,indices.z)*w.z;
    vec4 color=colorAt(vertices,m,indices.x)*w.x+colorAt(vertices,m,indices.y)*w.y+colorAt(vertices,m,indices.z)*w.z;
    // UV0 already addresses the vanilla normalized atlas. No sprite IDs or parallel atlas.
    // Explicit base mip: closest-hit has no fragment derivatives. UV2 remains preserved, not lit here.
    hit.color=textureLod(blockAtlas,hit.uv,0.0).rgb*color.rgb;
    hit.sampled=1u;
}
