#version 460
#extension GL_EXT_ray_tracing : require
#extension GL_EXT_buffer_reference2 : require
#extension GL_EXT_buffer_reference_uvec2 : require

struct Hit { vec3 color; uint found; ivec3 section; uint primitive; vec2 uv; uint sampled; ivec2 atlasSize; vec2 quadSpan; uint mode; int levels; vec4 baseTint; vec4 overlayTint; vec4 sampleColor; vec2 baseUv; uint overlayState; uint face; uint layer; uvec4 alphaStats; vec4 cutoutSample; ivec4 cutoutSection; float distance; float alpha; uvec2 previous; uvec4 transStats; vec4 transSample; ivec4 transSection; };
layout(location=0) rayPayloadInEXT Hit hit;
hitAttributeEXT vec2 barycentric;
struct Material { uvec4 addressLayout; uvec4 attributes; ivec4 section; uvec4 overlay; uvec4 mapping; uvec4 indices; };
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
vec4 sampleAtlas(vec2 uv,uint mode) {
    ivec2 size=textureSize(blockAtlas,0);
    ivec2 texel=clamp(ivec2(floor(clamp(uv,vec2(0),vec2(1))*vec2(size))),ivec2(0),size-ivec2(1));
    return mode==0u ? texelFetch(blockAtlas,texel,0) : textureLod(blockAtlas,uv,0.0);
}
vec3 positionAt(Vertices vertices,Material m,uint index) {
    uint b=index*m.addressLayout.z+m.mapping.w;
    return vec3(uintBitsToFloat(vertices.words[b]),uintBitsToFloat(vertices.words[b+1u]),uintBitsToFloat(vertices.words[b+2u]));
}
// Snapshot of vanilla's real sorted indices. SHORT loads are packed uint words (no shaderInt16).
uvec3 triangleIndices(Material m,uint primitive) {
    uint base=(primitive/2u)*4u;
    if(m.indices.z==0u) return base+((primitive&1u)==0u?uvec3(0,1,2):uvec3(2,3,0));
    uint first=primitive*3u;
    if(first+2u>=m.indices.w) return uvec3(0xffffffffu);
    Vertices indexData=Vertices(m.indices.xy);
    uvec3 result;
    for(uint corner=0u;corner<3u;corner++) {
        uint index=first+corner;
        result[corner]=m.indices.z==2u ? ((indexData.words[index/2u]>>((index&1u)*16u))&65535u) : indexData.words[index];
    }
    return result;
}
void main() {
    // gl_InstanceID is the TLAS input row, independent of unchanged customIndex/SBT offsets.
    hit.found=1u; hit.sampled=0u; hit.distance=gl_HitTEXT; hit.alpha=1.0; hit.layer=0u; hit.primitive=uint(gl_PrimitiveID);
    hit.color=vec3(1,0,1); // Invalid metadata must be visibly different from a successful atlas sample.
    if (gl_InstanceID >= materials.rows.length()) return;
    Material m=materials.rows[gl_InstanceID];
    hit.section=m.section.xyz; hit.layer=(uint(m.section.w)&4u)!=0u ? 2u : uint(m.section.w)&1u;
    if (uint(gl_PrimitiveID)>=m.attributes.z) return;
    uvec3 indices=triangleIndices(m,uint(gl_PrimitiveID));
    uint base=(indices.x/4u)*4u;
    if (any(greaterThanEqual(indices,uvec3(m.attributes.y)))) return;
    Vertices vertices=Vertices(m.addressLayout.xy);
    vec3 w=vec3(1.0-barycentric.x-barycentric.y,barycentric);
    hit.uv=uvAt(vertices,m,indices.x)*w.x+uvAt(vertices,m,indices.y)*w.y+uvAt(vertices,m,indices.z)*w.z;
    vec4 color=colorAt(vertices,m,indices.x)*w.x+colorAt(vertices,m,indices.y)*w.y+colorAt(vertices,m,indices.z)*w.z;
    // Vanilla's LINEAR sampler is paired with sampleNearest/RGSS in terrain.fsh. Bare textureLod
    // (0.5.0) omitted its texel-position correction, softening magnified pixel-art textures.
    // Reference/correction: read the actual mip-0 texel of the SAME view, bypassing filtering.
    // This is not a replacement for vanilla's derivative-based minification/RGSS.
    hit.atlasSize=textureSize(blockAtlas,0);
    hit.mode=m.attributes.w;
    if (any(isnan(hit.uv)) || any(isinf(hit.uv)) || any(lessThanEqual(hit.atlasSize,ivec2(0)))) return;
    ivec2 texel=clamp(ivec2(floor(clamp(hit.uv,0.0,1.0)*vec2(hit.atlasSize))),ivec2(0),hit.atlasSize-1);
    vec4 albedo=hit.mode==1u ? textureLod(blockAtlas,hit.uv,0.0) : texelFetch(blockAtlas,texel,0);
    hit.baseTint=color; hit.overlayTint=vec4(0); hit.baseUv=hit.uv; hit.overlayState=0u;
    if(any(notEqual(m.mapping.xy,uvec2(0)))) {
        Vertices mapping=Vertices(m.mapping.xy);
        uint mapped=mapping.words[base];
        if(mapped==0xfffffffeu) hit.overlayState=3u;
        else if(mapped!=0xffffffffu) {
            Vertices overlay=Vertices(m.overlay.xy);
            Material om=m; om.addressLayout.zw=m.overlay.zw; om.attributes.x=m.mapping.z;
            uint oi0=mapping.words[indices.x],oi1=mapping.words[indices.y],oi2=mapping.words[indices.z];
            vec2 overlayUv=uvAt(overlay,om,oi0)*w.x+uvAt(overlay,om,oi1)*w.y+uvAt(overlay,om,oi2)*w.z;
            vec4 overlayColor=colorAt(overlay,om,oi0)*w.x+colorAt(overlay,om,oi1)*w.y+colorAt(overlay,om,oi2)*w.z;
            if(!any(isnan(overlayUv)) && !any(isinf(overlayUv))) {
                vec4 overlaySample=sampleAtlas(overlayUv,hit.mode);
                hit.overlayTint=overlayColor; hit.overlayState=1u;
                // Vanilla CUTOUT_TERRAIN: sample * Color alpha, cutoff .5; overwrite, NOT blend.
                if(overlaySample.a*overlayColor.a>=0.5) {
                    hit.uv=overlayUv; color=overlayColor; albedo=overlaySample; hit.overlayState=2u;
                }
            }
        }
    }
    hit.alpha=hit.layer==2u ? clamp(albedo.a*color.a,0.0,1.0) : 1.0;
    hit.previous=uvec2(uint(gl_InstanceID),uint(gl_PrimitiveID));
    hit.sampleColor=albedo;
    hit.color=albedo.rgb*color.rgb;
    hit.sampled=1u;
    // Extra UV reads/queries ONLY for the optional single center-ray probe, never every image ray.
    if (all(equal(gl_LaunchSizeEXT.xy,uvec2(1)))) {
        vec3 normal=cross(positionAt(vertices,m,indices.y)-positionAt(vertices,m,indices.x),positionAt(vertices,m,indices.z)-positionAt(vertices,m,indices.x));
        if(dot(normal,gl_ObjectRayDirectionEXT)>0.0) normal=-normal;
        vec3 axis=abs(normal);
        hit.face=axis.y>=axis.x && axis.y>=axis.z ? (normal.y>=0.0?1u:0u) : axis.x>=axis.z ? (normal.x>=0.0?5u:4u) : (normal.z>=0.0?3u:2u);
        vec2 a=uvAt(vertices,m,base), b=uvAt(vertices,m,base+1u);
        vec2 c=uvAt(vertices,m,base+2u), d=uvAt(vertices,m,base+3u);
        hit.quadSpan=(max(max(a,b),max(c,d))-min(min(a,b),min(c,d)))*vec2(hit.atlasSize);
        hit.levels=textureQueryLevels(blockAtlas);
    }
}
