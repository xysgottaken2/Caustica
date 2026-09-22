// Closest-hit shader. Geometry is per-section: InstanceID() indexes a section table (BDA array reached
// from the push constant) holding this section's {prim, uv} buffer addresses. Per-primitive
// {normal, tint} plus barycentric atlas UVs produce the payload guides; lighting is deferred to raygen.
//
// tableAddr/entityTableAddr are read from the inline push-constant lanes (pc), not through the
// WorldPush BDA struct: they're read on every single hit (the hottest chain in the frame), and going
// through worldPushAddr first would cost an extra global-memory load just to find the address of the
// load we actually want. The cold WorldPush struct is dereferenced only for the breaking overlay.
import world_common;
import math;
import portals;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(2, 0)]] Sampler2D blockAlbedoAtlas;
// Independent bindless arrays: per-draw entity albedo slots and compact canonical material-page bundles.
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];

void payloadSetPacked(inout Payload payload, uint material, float roughness, float metalness,
        float emission, float sss, float ior, float transmission, uint emissionSource) {
    payload.flags = material | (emissionSource << PAYLOAD_EMISSION_SOURCE_SHIFT);
    payload.roughMetal = packHalf2(float2(roughness, metalness));
    payload.emissionSss = packHalf2(float2(emission, sss));
    payload.iorTransmission = packHalf2(float2(ior, transmission));
}

// Which side of a dielectric face this hit is on, shared by every hit path. Comes from the face
// orientation, so it is re-derived at each crossing instead of toggled (see PAYLOAD_DIELECTRIC_ENTERING).
void payloadSetDielectric(inout Payload payload, uint material, bool entering) {
    if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) return;
    if (entering) payload.flags |= PAYLOAD_DIELECTRIC_ENTERING;
}

uint materialEmissionSource(MaterialHeader header, float emission) {
    if (emission <= 0.0) return EMISSION_SOURCE_NONE;
    if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) return EMISSION_SOURCE_LAB_PBR;
    if ((header.features & MATERIAL_FEATURE_HEURISTIC_EMISSION) != 0u) return EMISSION_SOURCE_HEURISTIC;
    return EMISSION_SOURCE_UNIFORM;
}

// Strength of the LabPBR _n ambient-occlusion (blue) applied to albedo. Kept mild because the path
// tracer already computes its own AO from sky-visibility rays (avoid double-darkening).
static const float NORMAL_AO_STRENGTH = 0.5;

static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
static const float RAY_CONE_MIN_TEXEL_FOOTPRINT = 1.0e-4;
static const float RAY_CONE_MAX_LOD = 12.0;

float2 texSizePx(Sampler2D t) {
    uint w, h;
    t.GetDimensions(w, h);
    return float2(w, h);
}

float rayConeHitWidth(uint rayCone) {
    float2 cone = unpackHalf2(rayCone);
    return max(cone.x + cone.y * max(RayTCurrent(), 0.0), RAY_CONE_MIN_WIDTH);
}

float edgeTexelsPerWorld(float3 p0, float3 p1, float2 uv0, float2 uv1, float2 textureSizePx) {
    float worldLen = length(p1 - p0);
    if (worldLen <= 1.0e-7) {
        return 0.0;
    }
    return length((uv1 - uv0) * textureSizePx) / worldLen;
}

float rayConeTextureLod(uint rayCone, float2 textureSizePx, float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2) {
    float texelsPerWorld = max(edgeTexelsPerWorld(p0, p1, uv0, uv1, textureSizePx),
            max(edgeTexelsPerWorld(p1, p2, uv1, uv2, textureSizePx),
                edgeTexelsPerWorld(p2, p0, uv2, uv0, textureSizePx)));
    float footprint = max(rayConeHitWidth(rayCone) * texelsPerWorld, RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

float rayConeUnitUvLod(uint rayCone, float2 textureSizePx) {
    float footprint = max(rayConeHitWidth(rayCone) * max(textureSizePx.x, textureSizePx.y), RAY_CONE_MIN_TEXEL_FOOTPRINT);
    return clamp(log2(footprint), 0.0, RAY_CONE_MAX_LOD);
}

// LabPBR _n decode (shared): rotate the tangent-space normal into world space via a TBN built from the
// hit triangle's vertex positions (VK_KHR_ray_tracing_position_fetch) + UVs. `n` must already be
// oriented toward the viewer (`vdir`). Clamps the result above the horizon so grazing perturbations
// don't invert it through the surface (the black-spot fix). Returns AO (blue) via `ao`; falls back to
// `n` on a degenerate UV triangle. Instance transforms are translation-only, so object edges equal
// world edges.
float3 perturbNormal(float3 n, float3 p0, float3 p1, float3 p2, float2 t0, float2 t1, float2 t2, float3 vdir, float4 ntex, out float ao) {
    ao = 1.0;
    float2 g1 = t1 - t0;
    float2 g2 = t2 - t0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return n;
    }
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));       // Gram-Schmidt against the viewer-oriented normal
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);   // handedness from the UV winding
    float2 nxy = ntex.xy * 2.0 - 1.0;                    // R,G = tangent-space X,Y
    float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));      // reconstruct Z
    float3 nm = normalize(nxy.x * T + nxy.y * B + nz * n);
    float NoV = dot(nm, vdir);
    if (NoV < 0.02) {
        nm = normalize(nm + vdir * (0.02 - NoV));        // keep above the horizon (no flip)
    }
    ao = lerp(1.0, ntex.b, NORMAL_AO_STRENGTH);
    return nm;
}

struct Surface {
    float3 normal;
    float3 f0;
    float roughness;
    float metalness;
    float emission;
    float sss;
    float ao;
};

// Canonical material pages use a compact page index shared by their three dedicated arrays.
// When height-map smoothing is disabled (WORLD_FLAG_POM_SMOOTHING off), every LabPBR channel snaps to
// exact mip texel centres, so "off" is genuinely pixel-aligned rather than merely applying nearest
// filtering to some channels while leaving the mapped normals smooth. The height field itself is not
// affected by the toggle any more: POM intersects it as per-texel columns, which is texel-exact by
// construction (parallaxColumnHeight).
bool pomSmoothingEnabled() {
    return (ConstPtr<WorldPush>(pc.worldPushAddr)[0].flags & WORLD_FLAG_POM_SMOOTHING) != 0u;
}

float2 snapUvToTexelCentre(Sampler2D pageTex, float2 uv, inout float lod) {
    uint w, h;
    pageTex.GetDimensions(w, h);
    float mip = floor(lod);
    float2 size = max(float2(1.0, 1.0), floor(float2(w, h) / exp2(mip)));
    lod = mip;
    return (floor(uv * size) + 0.5) / size;
}

float4 samplePageSurface0(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialSurface0Tex[NonUniformResourceIndex(page)];
    if (!pomSmoothingEnabled()) uv = snapUvToTexelCentre(pageTex, uv, lod);
    return pageTex.SampleLevel(uv, lod);
}
float4 samplePageSurface1(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialSurface1Tex[NonUniformResourceIndex(page)];
    if (!pomSmoothingEnabled()) uv = snapUvToTexelCentre(pageTex, uv, lod);
    return pageTex.SampleLevel(uv, lod);
}
float4 samplePageNormalAo(uint page, float2 uv, float lod) {
    Sampler2D pageTex = materialNormalAoTex[NonUniformResourceIndex(page)];
    if (!pomSmoothingEnabled()) uv = snapUvToTexelCentre(pageTex, uv, lod);
    return pageTex.SampleLevel(uv, lod);
}

// ---- Parallax Occlusion Mapping -------------------------------------------------------------------
//
// The LabPBR height field (alpha of the _n page) is intersected as REAL geometry, exactly the way the
// classic cloud deck is (see cloudClassicBoxes in clouds.slang): every height texel is a box column
// standing on the base plane, and the view ray walks that 2D texel grid — Amanatides & Woo, the same
// walk the cloud cells use — stopping at the first column it cannot pass. That is what a layer march
// fundamentally cannot do: sampling the field at N fixed depths only ever produces a stack of flat
// slices, so a grazing or very close view sees the slices themselves, and sees THROUGH them, because a
// layer has no sides. Walking the grid gives every texel its four side walls for free. The hit is
// either a column TOP (flat, keeps the LabPBR normal map) or a column SIDE WALL, reported with the
// wall's own normal so it shades — and visually occludes — like the solid face it stands for.
//
// The base plane at relief depth 1 closes the volume, so the walk always terminates on a surface; a
// ray never escapes out of the bottom of the field.
//
// Cost per step is the same single height sample the layer march paid, but a step is now a texel
// crossing: a head-on view resolves in one sample instead of always paying the full layer count, and
// only grazing views walk far. parallaxParams.y bounds that walk (see PARALLAX_MIN/MAX_CROSSINGS).

struct ParallaxHit {
    float2 uv;      // atlas UV to shade with (displaced when the height field moved the hit)
    float3 normal;  // world-space normal of the surface that was actually hit
    bool wall;      // true when a column side wall was hit: `normal` is a tangent axis, not the face normal
};

ParallaxHit parallaxFlat(float2 atlasUv, float3 geometricNormal) {
    ParallaxHit hit;
    hit.uv = atlasUv;
    hit.normal = geometricNormal;
    hit.wall = false;
    return hit;
}

// Height of one column. The columns ARE texels, so this always samples the texel centre of a whole mip
// level: a bilinear tap between two texels would round the box corners back off and reintroduce exactly
// the smeared, sideless relief the grid walk exists to remove. Out-of-range cells wrap into the sprite
// so the caller can walk past the sprite border without ever addressing a neighbouring atlas sprite.
float parallaxColumnHeight(MaterialHeader header, int2 cell, float2 grid, float mip) {
    float2 wrapped = float2(cell) - floor(float2(cell) / grid) * grid;
    float2 pageUv = header.materialUv.xy + ((wrapped + 0.5) / grid) * header.materialUv.zw;
    return materialNormalAoTex[NonUniformResourceIndex(header.texturePage)].SampleLevel(pageUv, mip).a;
}

// Column grid of one sprite at a whole mip level: one cell per height texel.
float2 parallaxGrid(MaterialHeader header, float mip) {
    uint pageWidth, pageHeight;
    materialNormalAoTex[NonUniformResourceIndex(header.texturePage)].GetDimensions(pageWidth, pageHeight);
    float2 pageTexels = max(float2(1.0, 1.0), floor(float2(pageWidth, pageHeight) / exp2(mip)));
    return max(float2(1.0, 1.0), floor(pageTexels * header.materialUv.zw + 0.5));
}

// Trace the height field for one hit. The configured camera distance keeps full relief through 80% of
// its range, then fades continuously so the cutoff cannot visibly pop.
//
// `repeat` says whether the sprite may tile. Block sprites do — a stone face is the same sprite the
// next block over shows — so the walk simply continues into the wrapped texel and the relief runs
// uninterrupted into the block border. That is what removes the old inward "suction" at block edges:
// the previous code faded the displacement to zero over the last texels of the sprite, which squeezed
// the whole border band toward the middle of the face. Entity textures are model atlases whose islands
// must not bleed into each other, so they keep the narrow border fade and stop at the sprite instead.
ParallaxHit parallaxTrace(MaterialHeader header, float2 atlasUv, float lod, float3 geometricNormal,
        float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2, float3 vdir, bool repeat) {
    ParallaxHit flatHit = parallaxFlat(atlasUv, geometricNormal);
    if ((header.features & MATERIAL_FEATURE_NORMAL) == 0u) {
        return flatHit;
    }

    float2 g1 = uv1 - uv0;
    float2 g2 = uv2 - uv0;
    float det = g1.x * g2.y - g1.y * g2.x;
    if (abs(det) <= 1.0e-12) {
        return flatHit;
    }

    float3 n = geometricNormal;
    float r = 1.0 / det;
    float3 traw = ((p1 - p0) * g2.y - (p2 - p0) * g1.y) * r;
    float3 T = normalize(traw - n * dot(n, traw));
    float3 B = cross(n, T) * (det < 0.0 ? -1.0 : 1.0);
    float rawNoV = max(dot(vdir, n), 0.0);
    float3 viewTs = float3(dot(vdir, T), dot(vdir, B), max(rawNoV, 0.08));

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    float4 parallaxParams = worldPush.parallaxParams;
    float maxDistance = max(parallaxParams.w, 1.0);
    float3 hitPosition = WorldRayOrigin() + WorldRayDirection() * RayTCurrent();
    float cameraDistance = length(hitPosition - worldPush.camOffset);
    float distanceFade = 1.0 - smoothstep(maxDistance * 0.8, maxDistance, cameraDistance);
    // Only suppress the singular near-horizontal case; a wider fade would override the distance slider.
    float grazingFade = smoothstep(0.002, 0.006, rawNoV);
    // Tiling sprites need no border fade at all — the walk wraps. Non-tiling (entity) sprites still get
    // one, because for them the walk has to stop at the border and a hard stop would pop.
    float edgeFade = 1.0;
    if (!repeat) {
        float edgeDistance = min(min(localUv.x, 1.0 - localUv.x), min(localUv.y, 1.0 - localUv.y));
        edgeFade = smoothstep(0.015, 0.09, edgeDistance);
    }
    float reliefDepth = parallaxParams.x * distanceFade * grazingFade * edgeFade;
    if (reliefDepth <= 1.0e-5) {
        return flatHit;
    }

    // Column grid: one cell per texel of the sprite at this mip. The walk needs a whole mip level (a
    // fractional LOD would blend two differently sized grids), so the ray-cone LOD is floored here.
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float mip = clamp(floor(lod), 0.0, maxMaterialLod);
    int maxCrossings = clamp(int(round(parallaxParams.y)), PARALLAX_MIN_CROSSINGS, PARALLAX_MAX_CROSSINGS);
    float2 grid = parallaxGrid(header, mip);

    // Walk parameter is the relief depth itself: z = 0 at the geometric face, z = 1 at the base plane.
    // fullDelta is the local UV the ray travels over that whole range, so the crossings the walk would
    // need are its extent in texels. A very grazing view of a high-resolution pack can need thousands;
    // rather than truncate the ray (which smears a band exactly where the relief is most visible), drop
    // to a coarser mip — fewer, bigger columns — until the full depth range fits inside the budget.
    float2 fullDelta = -(viewTs.xy / viewTs.z) * reliefDepth;
    float needed = abs(fullDelta.x) * grid.x + abs(fullDelta.y) * grid.y + 1.0;
    if (needed > float(maxCrossings)) {
        mip = min(mip + ceil(log2(needed / float(maxCrossings))), maxMaterialLod);
        grid = parallaxGrid(header, mip);
    }

    float2 texelPos = localUv * grid;
    float2 dirTexels = fullDelta * grid;   // texels travelled per unit of relief depth
    int2 cell = clamp(int2(floor(texelPos)), int2(0, 0), int2(grid) - int2(1, 1));
    int2 stepDir = int2(dirTexels.x >= 0.0 ? 1 : -1, dirTexels.y >= 0.0 ? 1 : -1);
    float2 tDelta = float2(abs(dirTexels.x) > 1.0e-8 ? 1.0 / abs(dirTexels.x) : 1.0e30,
                           abs(dirTexels.y) > 1.0e-8 ? 1.0 / abs(dirTexels.y) : 1.0e30);
    float2 edge = float2(stepDir.x > 0 ? float(cell.x) + 1.0 : float(cell.x),
                         stepDir.y > 0 ? float(cell.y) + 1.0 : float(cell.y));
    float2 tMax = float2(abs(dirTexels.x) > 1.0e-8 ? (edge.x - texelPos.x) / dirTexels.x : 1.0e30,
                         abs(dirTexels.y) > 1.0e-8 ? (edge.y - texelPos.y) / dirTexels.y : 1.0e30);

    float enterDepth = 0.0;   // depth at which the ray entered `cell`
    int enterAxis = -1;       // 0 = crossed a u wall, 1 = a v wall, -1 = started here (no wall behind it)
    float hitDepth = 0.0;
    int wallAxis = -1;
    bool resolved = false;

    [loop]
    for (int crossing = 0; crossing < maxCrossings; ++crossing) {
        // A column occupies [top, 1] in depth; top == 0 is a texel flush with the face, top == 1 an
        // empty cell that only the base plane closes.
        float top = 1.0 - parallaxColumnHeight(header, cell, grid, mip);
        float exitDepth = min(min(tMax.x, tMax.y), 1.0);
        if (exitDepth >= top) {
            // The ray reaches this column's height before leaving the cell, so this is the hit. If it
            // was already below the top when it entered, it never saw the top face: it ran into the
            // side WALL at the cell boundary — the surface a layer march has no representation for.
            resolved = true;
            if (enterAxis >= 0 && enterDepth > top) {
                hitDepth = enterDepth;
                wallAxis = enterAxis;
            } else {
                hitDepth = max(top, enterDepth);
            }
            break;
        }
        if (tMax.x < tMax.y) {
            enterDepth = tMax.x;
            cell.x += stepDir.x;
            tMax.x += tDelta.x;
            enterAxis = 0;
        } else {
            enterDepth = tMax.y;
            cell.y += stepDir.y;
            tMax.y += tDelta.y;
            enterAxis = 1;
        }
        if (!repeat && (any(cell < int2(0, 0)) || any(cell >= int2(grid)))) {
            return flatHit; // model atlas: leaving the sprite must not sample a neighbouring island
        }
    }
    if (!resolved) {
        // Crossing budget spent (very grazing view, very deep relief, or a high-resolution pack): stop
        // the ray where it stands instead of snapping back to the flat face, which would pop.
        hitDepth = enterDepth;
    }

    float2 hitTexelPos = texelPos + dirTexels * hitDepth;
    if (wallAxis >= 0) {
        // A wall belongs to the column it closes, so shade it with that column's own texel: sampling
        // exactly on the boundary would blend in the neighbour the ray just left.
        if (wallAxis == 0) {
            hitTexelPos.x = float(cell.x) + 0.5;
        } else {
            hitTexelPos.y = float(cell.y) + 0.5;
        }
    }
    float2 hitLocalUv = hitTexelPos / grid;
    hitLocalUv = repeat ? hitLocalUv - floor(hitLocalUv)
                        : clamp(hitLocalUv, float2(0.0, 0.0), float2(1.0, 1.0));

    ParallaxHit hit;
    hit.uv = header.albedoUv.xy + hitLocalUv / header.albedoUv.zw;
    hit.wall = wallAxis >= 0;
    hit.normal = geometricNormal;
    if (wallAxis >= 0) {
        // A wall faces back along the axis the ray crossed, so it always faces the viewer by
        // construction. It is tilted PARALLAX_WALL_TILT toward the face because the renderer reuses the
        // shading normal as the ray-spawn basis: a normal exactly parallel to the face would offset the
        // next bounce along the face plane and send half of its cosine hemisphere into the solid block.
        // The tangent-space normal map stays out of it — it is authored for the face, not for a side.
        float3 wall = wallAxis == 0 ? T * float(-stepDir.x) : B * float(-stepDir.y);
        hit.normal = normalize(wall + geometricNormal * PARALLAX_WALL_TILT);
    }
    return hit;
}

// The sole terrain/entity material evaluation path. LabPBR categorical bytes have already been
// decoded on the CPU; every sample below is a continuously filterable physical channel with semantic mips.
Surface evaluateMaterial(MaterialHeader header, float2 atlasUv, float lod, float3 albedo,
        float3 geometricNormal, float3 p0, float3 p1, float3 p2,
        float2 uv0, float2 uv1, float2 uv2, float3 vdir,
        float fallbackRoughness, float fallbackMetalness, float fallbackEmission, bool allowSss) {
    Surface surface;
    surface.normal = geometricNormal;
    surface.roughness = fallbackRoughness;
    surface.metalness = fallbackMetalness;
    surface.f0 = lerp(float3(0.04, 0.04, 0.04), albedo, fallbackMetalness);
    surface.emission = fallbackEmission;
    surface.sss = 0.0;
    surface.ao = 1.0;

    float2 localUv = clamp((atlasUv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    float materialLod = min(lod, maxMaterialLod);
    uint page = header.texturePage;
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION)) != 0u) {
        float4 surface0 = samplePageSurface0(page, pageUv, materialLod);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = samplePageSurface1(page, pageUv, materialLod); // LabPBR F0
            surface.roughness = surface0.r;
            surface.metalness = surface0.g;
            surface.emission = surface0.b;
            surface.sss = allowSss ? surface0.a : 0.0;
            surface.f0 = surface1.rgb;
        } else {
            // Eligibility is compiled from block-state semantics; the primitive's exact light level
            // supplies strength, while the page supplies only the normalized spatial mask.
            surface.emission = surface0.b * fallbackEmission;
        }
    }
    // Final HDR emission strength (EMISSIVE_STRENGTH baseline * any JSON multiplier), baked in Java at
    // material-compile time and packed unconditionally — see MATERIAL_EMISSION_STRENGTH_SHIFT. 0 for
    // non-emissive materials, so this multiply is always safe (and a no-op) when nothing above ran.
    // Runtime global light scales are applied later in raygen so block and dynamic emitters can differ.
    float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
            & MATERIAL_EMISSION_STRENGTH_MASK)
            * (MATERIAL_MAX_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
    if (strength <= 0.0 && fallbackEmission > 0.0) {
        strength = MATERIAL_DEFAULT_DYNAMIC_EMISSION_STRENGTH;
    }
    surface.emission *= strength;
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = samplePageNormalAo(page, pageUv, materialLod);
        surface.normal = perturbNormal(geometricNormal, p0, p1, p2, uv0, uv1, uv2,
                vdir, normalAo, surface.ao);
    }
    return surface;
}

// Block-breaking overlay: shared by opaque terrain AND stained-glass/ice. Reconstructs the hit position
// in the same rebased space the breaking list was pushed in, nudges it into the solid block along
// -normal to get the block's integer coordinate, then decal-projects the local [0,1] position onto
// whichever axis pair the normal is most aligned with (same idea as vanilla's
// SheetedDecalTextureGenerator) and multiply-blends the matching destroy-stage crack texture in,
// mirroring vanilla's crumbling blend (DST_COLOR*SRC_COLOR, doubled).
float3 applyBreaking(float3 albedo, uint rayCone, float3 rayOrigin, float3 rayDir, float hitT, float3 n) {
    ConstPtr<WorldPush> pcRef = ConstPtr<WorldPush>(pc.worldPushAddr);
    uint breakCount = pcRef[0].breakCount;
    if (breakCount == 0u) {
        return albedo;
    }
    float3 hitPos = rayOrigin + rayDir * hitT;
    int3 blockPos = int3(floor(hitPos - n * 0.01));
    for (uint bi = 0u; bi < breakCount; ++bi) {
        int4 ps = pcRef[0].breaking[bi].posSlot;
        if (all(ps.xyz == blockPos)) {
            float3 local = hitPos - float3(blockPos);
            float3 an = abs(n);
            float2 decalUv = (an.x >= an.y && an.x >= an.z) ? local.zy
                           : (an.y >= an.z) ? local.xz
                           : local.xy;
            float decalLod = rayConeUnitUvLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(ps.w)]));
            float3 crack = srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(ps.w)].SampleLevel(decalUv, decalLod).rgb);
            return clamp(crack * albedo, 0.0, 1.0);
        }
    }
    return albedo;
}

// Distant Horizons / Voxy mini-material shading defaults. The coarse VBO retains only a one-byte
// material class, so these map that class onto plausible PBR parameters.
float dhMaterialRoughness(uint material, float fallbackRoughness) {
    if (material == DH_MATERIAL_METAL) return 0.28;
    if (material == DH_MATERIAL_SNOW) return 0.38;
    if (material == DH_MATERIAL_WOOD) return 0.72;
    if (material == DH_MATERIAL_STONE) return 0.78;
    if (material == DH_MATERIAL_DEEPSLATE) return 0.84;
    if (material == DH_MATERIAL_TERRACOTTA) return 0.76;
    if (material == DH_MATERIAL_LEAVES) return 0.84;
    if (material == DH_MATERIAL_GRASS) return 0.92;
    if (material == DH_MATERIAL_DIRT) return 0.96;
    if (material == DH_MATERIAL_SAND) return 0.98;
    if (material == DH_MATERIAL_NETHER_STONE) return 0.88;
    if (material == DH_MATERIAL_LAVA) return 0.68;
    if (material == DH_MATERIAL_ILLUMINATED) return clamp(fallbackRoughness, 0.18, 0.90);
    return clamp(fallbackRoughness, 0.25, 1.0);
}

float dhMaterialMetalness(uint material, float fallbackMetalness) {
    if (material == DH_MATERIAL_METAL) return 1.0;
    if (material == DH_MATERIAL_ILLUMINATED) return clamp(fallbackMetalness, 0.0, 1.0);
    return 0.0;
}

float dhMaterialSss(uint material) {
    if (material == DH_MATERIAL_LEAVES) return 0.18;
    if (material == DH_MATERIAL_GRASS) return 0.055;
    if (material == DH_MATERIAL_SNOW) return 0.08;
    return 0.0;
}

// ---- Enchantment glint --------------------------------------------------------------------------
// Vanilla's glint is an animated, unlit shimmer: its fragment shader discards texels below 0.1 alpha
// and outputs the raw texture colour with no lightmap, while the sample UV scrolls diagonally via a
// per-frame TextureMat (GLINT_SCROLL_* / glintScrolledUv in world_common, shared with world.rahit so
// the coverage gate and the shading sample move together). In 26.x the glint textures carry NO alpha
// (the armour glint is a plain grayscale map), so the shimmer look actually comes from the additive
// pipeline blend (BlendFunction.GLINT: dst + src^2) and the pattern lives in the texel BRIGHTNESS.
// world.rahit reproduces that blend as per-brightness stochastic coverage; the rays that reach this
// branch therefore shade a real, bright streak. The purple comes from the texture being purple by
// default (a resource pack can override it to any colour); grayscale textures get a vanilla-like
// purple multiplier so the streak reads as the familiar enchantment colour. Self-lit (emission =
// luminance * GLINT_EMISSION) keeps the shimmer bright over the armour instead of being darkened by
// scene light — vanilla's glint ignores lighting entirely.
static const float GLINT_EMISSION = 3.0;
// Tint for grayscale glint textures (the vanilla 26.x armour map). The classic enchantment purple in
// linear space, modulated by the texel value; packs that ship an actual colour are used as-is.
static const float3 GLINT_GRAYSCALE_TINT = float3(1.30, 0.42, 1.55);

[shader("closesthit")]
void main(inout Payload payload, in BuiltInTriangleIntersectionAttributes attr) {
    float2 attribs = attr.barycentrics;
    uint rayCone = payload.rayCone;

    // Native DH/Voxy LOD triangles participate in camera, GI, reflection and shadow rays through the
    // same TLAS. They carry no sprite: shading comes from vertex colour + the mini-material class.
    if ((InstanceID() & DH_INSTANCE_KIND) == DH_INSTANCE_KIND) {
        Section sec = ConstPtr<Section>(pc.dhTableAddr)[InstanceID() & IDX_MASK];
        uint dpid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
        TerrainPrim dpr = ConstPtr<TerrainPrim>(sec.primAddr)[dpid];
        float3 sourceNormal = normalize(dpr.normal.xyz);
        float3 dn = sourceNormal;
        if (dot(dn, -WorldRayDirection()) < 0.0) dn = -dn;
        uint dhSurface = uint(dpr.tint.w + 0.5);
        uint dhMaterial = dpr.flags;
        bool dhWater = dhSurface == DH_SURFACE_WATER;
        bool dhGlass = dhSurface == DH_SURFACE_GLASS;
        bool dhEmissive = dhSurface == DH_SURFACE_EMISSIVE;
        bool dhEmissiveGlass = dhSurface == DH_SURFACE_EMISSIVE_GLASS;
        float dhEmission = (dhEmissive || dhEmissiveGlass) ? clamp(asfloat(dpr.aux0), 0.0, 4.0) : 0.0;
        MaterialHeader dhHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[dpr.materialId];

        if (dhGlass || dhEmissiveGlass) {
            float opticalAlpha = clamp(asfloat(dhEmissiveGlass ? dpr.aux1 : dpr.aux0), 0.04, 0.80);
            float3 glassTint = glassTransmissionFilter(dpr.tint.rgb, opticalAlpha);
            float ior = max(dhHeader.params.z, 1.45);
            float f0Base = (ior - 1.0) / (ior + 1.0);
            float f0Scalar = f0Base * f0Base;
            payload.albedo = half3(glassTint);
            payload.normal = half3(dn);
            payload.hitT = RayTCurrent();
            payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
            payload.f0 = half3(float3(f0Scalar, f0Scalar, f0Scalar));
            payloadSetPacked(payload, MATERIAL_DIELECTRIC,
                    clamp(dhHeader.params.x, 0.025, 0.16), 0.0, dhEmission, 0.0,
                    ior, max(dhHeader.params.w, 0.97),
                    dhEmissiveGlass ? EMISSION_SOURCE_UNIFORM : EMISSION_SOURCE_NONE);
            payloadSetDielectric(payload, MATERIAL_DIELECTRIC,
                    dot(WorldRayDirection(), sourceNormal) < 0.0);
            return;
        }

        if (dhWater) {
            // A coarse LOD voxel is a sparse surface approximation, not a watertight column: pushing a
            // real water medium here would accumulate Beer-Lambert absorption through generated
            // bottom/side faces and turn distant lakes into black sheets (the fork's voxy-compat bug).
            // Our unified dielectric raygen derives the medium from the payload, so the safe adaptation
            // is a glossy opaque water-tinted surface: correct colour and a plausible sun highlight at
            // horizon distances, with zero medium-stack interaction.
            payload.albedo = half3(dpr.tint.rgb);
            payload.normal = half3(dn);
            payload.hitT = RayTCurrent();
            payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
            payload.f0 = half3(0.02h, 0.02h, 0.02h);
            payloadSetPacked(payload, MATERIAL_OPAQUE, 0.075, 0.0, 0.0, 0.0,
                    1.333, 0.0, EMISSION_SOURCE_NONE);
            return;
        }

        payload.albedo = half3(dpr.tint.rgb);
        payload.normal = half3(dn);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        float dhRoughness = dhMaterialRoughness(dhMaterial, dhHeader.params.x);
        float dhMetalness = dhMaterialMetalness(dhMaterial, dhHeader.params.y);
        float dhSss = dhMaterialSss(dhMaterial);
        payload.f0 = half3(lerp(float3(0.04, 0.04, 0.04), dpr.tint.rgb, dhMetalness));
        payloadSetPacked(payload, MATERIAL_OPAQUE, dhRoughness, dhMetalness, dhEmission, dhSss,
                max(dhHeader.params.z, 1.0), 0.0,
                dhEmissive ? EMISSION_SOURCE_UNIFORM : EMISSION_SOURCE_NONE);
        return;
    }

    // Particle billboard: same geom-table/UV/bindless-atlas path as entities. Albedo is atlas texel *
    // per-particle colour; raygen treats material 2 as a primary-visible diffuse receiver with no
    // emission or specular response. Reached only by the primary ray (instance mask 0x02). Cutout: rahit.
    if ((InstanceID() & PARTICLE_BIT) != 0u) {
        uint idx = InstanceID() & IDX_MASK;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[idx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        ConstPtr<uint> ib = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> uvb = ConstPtr<float2>(g.uvAddr);
        uint p0 = ib[3u * pid + 0u];
        uint p1 = ib[3u * pid + 1u];
        uint p2 = ib[3u * pid + 2u];
        float3 pbary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 puv = pbary.x * uvb[p0] + pbary.y * uvb[p1] + pbary.z * uvb[p2];
        int pslot = int(pr.tint.w + 0.5);
        float3x3 o2w = (float3x3)ObjectToWorld3x4();
        float3 pp0 = mul(o2w, HitTriangleVertexPosition(0));
        float3 pp1 = mul(o2w, HitTriangleVertexPosition(1));
        float3 pp2 = mul(o2w, HitTriangleVertexPosition(2));
        float particleLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(pslot)]),
                pp0, pp1, pp2, uvb[p0], uvb[p1], uvb[p2]);
        float3 pn = normalize(pr.normal.xyz);
        if (dot(pn, WorldRayDirection()) > 0.0) {
            pn = -pn;
        }
        payload.albedo = half3(srgbToLinear(entityAlbedoTex[NonUniformResourceIndex(pslot)].SampleLevel(puv, particleLod).rgb) * pr.tint.rgb);
        payload.normal = half3(pn);
        payload.hitT = RayTCurrent();
        // Per-particle motion vector: interpolate the captured per-vertex displacement (uniform across
        // the billboard's verts) with the same indices/barycentrics as the UV. dispAddr == 0 falls back
        // to rigidDisp, which particles write as zero.
        if (g.dispAddr != 0) {
            ConstPtr<float4> pd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = half3(pbary.x * pd[p0].xyz + pbary.y * pd[p1].xyz + pbary.z * pd[p2].xyz);
        } else {
            payload.motionPrev = half3(g.rigidDisp.xyz);
        }
        payload.f0 = half3(0.0h, 0.0h, 0.0h);
        payloadSetPacked(payload, MATERIAL_PARTICLE, 1.0, 0.0, 0.0, 0.0,
                1.0, 0.0, EMISSION_SOURCE_NONE);
        // Rain/snow sheets are tagged at capture (RtWeatherCapture) and shaded unlit in raygen — the
        // path tracer's light response was visibly breaking them (see PRIM_WEATHER).
        if ((pr.flags & PRIM_WEATHER) != 0u) {
            payload.flags |= PAYLOAD_WEATHER;
        }
        return;
    }
    // Dynamic entities: instances with this custom-index flag bit carry real captured ModelPart
    // geometry; the low bits index the entity geometry table, not the section table.
    if ((InstanceID() & ENTITY_BIT) != 0u) {
        uint eidx = InstanceID() & ~ENTITY_BIT;
        EntityGeom g = ConstPtr<EntityGeom>(pc.entityTableAddr)[eidx];
        uint pid = PrimitiveIndex() + g.triBase[GeometryIndex()];
        Prim pr = ConstPtr<Prim>(g.primAddr)[pid];
        // Rigid-reuse instances (still / spinning entities re-referencing a cached BLAS) carry a real
        // rotation in the instance transform; per-frame-built entities are identity. The cached prim
        // normal (and the TBN source positions below) are object-space — rotate them into world space.
        // Identity/translation instances make this a no-op.
        float3x3 entityO2w = (float3x3)ObjectToWorld3x4();
        // Orient the geometric normal toward the viewer first (so the _n TBN is built in the right
        // hemisphere — same fix as terrain).
        float3 vdir = -WorldRayDirection();
        float3 n = normalize(mul(entityO2w, pr.normal.xyz));
        bool entering = dot(WorldRayDirection(), n) < 0.0;
        if (dot(n, vdir) < 0.0) {
            n = -n;
        }
        // Interpolate the captured entity-texture UV (same scheme as terrain) and sample the bindless
        // per-type texture; tint.rgb is the model colour multiplier (white for most, tinted for sheep/…).
        ConstPtr<uint> eidxBuf = ConstPtr<uint>(g.idxAddr);
        ConstPtr<float2> euv = ConstPtr<float2>(g.uvAddr);
        uint e0 = eidxBuf[3u * pid + 0u];
        uint e1 = eidxBuf[3u * pid + 1u];
        uint e2 = eidxBuf[3u * pid + 2u];
        float3 ebary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
        float2 euvCoord = ebary.x * euv[e0] + ebary.y * euv[e1] + ebary.z * euv[e2];
        int texSlot = int(pr.tint.w + 0.5);
        float3 ep0 = mul(entityO2w, HitTriangleVertexPosition(0));
        float3 ep1 = mul(entityO2w, HitTriangleVertexPosition(1));
        float3 ep2 = mul(entityO2w, HitTriangleVertexPosition(2));
        float entityLod = rayConeTextureLod(rayCone, texSizePx(entityAlbedoTex[NonUniformResourceIndex(texSlot)]),
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2]);
        MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
        uint material = header.model;
        // Portal surfaces captured through the entity/block-entity path (EndPortalRenderer's quad, or a
        // held/displayed nether portal block): the same dedicated procedural shading as terrain portals,
        // so the flat end_portal texture never shows. The collector stamps TERRAIN_PRIM_PORTAL_* into
        // the entity prim's flags lane and forces the opaque bucket.
        if ((pr.flags & (TERRAIN_PRIM_PORTAL_NETHER | TERRAIN_PRIM_PORTAL_END)) != 0u) {
            WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
            float portalTime = worldPush.waterParams.w; // per-frame animation clock (seconds, wrapped)
            float3 portalColor;
            float portalEmission;
            if ((pr.flags & TERRAIN_PRIM_PORTAL_END) != 0u) {
                float3 nightSky = endPortalNightSky(WorldRayDirection(), portalTime,
                        worldPush.celestial);
                portalColor = nightSky * END_PORTAL_BRIGHTNESS;
                portalEmission = luminance(nightSky) * END_PORTAL_EMISSION;
            } else {
                // Nether swirl on entity-held portal blocks: same in-sprite scroll as terrain.
                float2 localUv = (euvCoord - header.albedoUv.xy) * header.albedoUv.zw;
                float2 scrolled = frac(localUv + float2(portalTime * NETHER_PORTAL_SCROLL_U,
                        portalTime * NETHER_PORTAL_SCROLL_V));
                float2 swirlUv = header.albedoUv.xy + scrolled / header.albedoUv.zw;
                float4 swirlTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(swirlUv, 0.0);
                float3 swirl = srgbToLinear(swirlTexel.rgb) * pr.tint.rgb * (0.35 + 0.65 * swirlTexel.a);
                portalColor = swirl;
                portalEmission = luminance(swirl) * NETHER_PORTAL_EMISSION;
            }
            payload.albedo = half3(portalColor);
            payload.normal = half3(n);
            payload.hitT = RayTCurrent();
            payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
            payload.f0 = half3(0.04h, 0.04h, 0.04h);
            payloadSetPacked(payload, MATERIAL_OPAQUE, 0.6, 0.0, portalEmission, 0.0,
                    1.0, 0.0, EMISSION_SOURCE_UNIFORM);
            payloadSetDielectric(payload, MATERIAL_OPAQUE, entering);
            // The End portal is fully unlit (like the terrain portal): raygen adds only its own
            // emission and ends the path, and the zero F0 guarantees no specular response — a
            // nearby torch can neither glint on nor illuminate the starfield. The Nether portal
            // keeps the ordinary lit surface above.
            if ((pr.flags & TERRAIN_PRIM_PORTAL_END) != 0u) {
                payload.f0 = half3(0.0h, 0.0h, 0.0h);
                payload.flags |= PAYLOAD_END_PORTAL_UNLIT;
            }
            return;
        }
        // Enchantment/foil glint overlay (armour, held enchanted items, tridents): special-captured
        // like the portal surfaces — the collector's PRIM_GLINT tag drives this dedicated branch, so
        // the glint is an animated, self-lit shimmer rather than a flat texture drawn at reduced
        // opacity. world.rahit has already gated the ray (texel-brightness dither AND the armour-texture
        // alpha gate, so the shimmer never paints the bare body), and the rays reaching here shade a
        // real streak.
        if ((pr.flags & PRIM_GLINT) != 0u) {
            WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
            float glintTime = worldPush.waterParams.w; // per-frame animation clock (same as portals)
            // Same scrolled UV world.rahit used for the coverage gate, so both sample the moving
            // pattern in lock-step (see glintScrolledUv in world_common).
            float2 glintUv = glintScrolledUv(header, euvCoord, glintTime);
            float4 glintTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(glintUv, entityLod);
            float3 glintRgb = srgbToLinear(glintTexel.rgb);
            // Grayscale 26.x glint maps (no colour channel) are tinted purple like vanilla's default
            // texture; actual coloured glint textures are used as-is.
            float grayscale = (abs(glintRgb.r - glintRgb.g) + abs(glintRgb.g - glintRgb.b)) * 0.5;
            if (grayscale < 0.02) {
                glintRgb = glintRgb * GLINT_GRAYSCALE_TINT;
            }
            // Unlit like vanilla's glint shader: the streak colour radiates (self-lit) instead of being
            // darkened by the scene, so the shimmer stays bright over the armour.
            float3 glintColor = glintRgb * pr.tint.rgb;
            payload.albedo = half3(glintColor);
            payload.normal = half3(n);
            payload.hitT = RayTCurrent();
            payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
            payload.f0 = half3(0.04h, 0.04h, 0.04h);
            payloadSetPacked(payload, MATERIAL_OPAQUE, 0.6, 0.0,
                    luminance(glintColor) * GLINT_EMISSION, 0.0, 1.0, 0.0, EMISSION_SOURCE_UNIFORM);
            payloadSetDielectric(payload, MATERIAL_OPAQUE, entering);
            // Entity emissive geometry (armour overlays, the flame, held lights): raygen applies the
            // dynamic/entity emission scale and two-sided lighting to it, like every other entity hit.
            payload.flags |= PAYLOAD_DYNAMIC_ENTITY;
            return;
        }
        // POM on the opaque entity BLAS geometry only; cutout/translucent entities keep the exact UV
        // whose alpha the any-hit shader already validated (block-entity models like chests are opaque).
        // Entity textures are model atlases, so the height walk may not tile out of the sprite.
        ParallaxHit entityParallax = parallaxFlat(euvCoord, n);
        if (GeometryIndex() == 0u && material == MATERIAL_OPAQUE) {
            entityParallax = parallaxTrace(header, euvCoord, entityLod, n, ep0, ep1, ep2,
                    euv[e0], euv[e1], euv[e2], vdir, false);
            euvCoord = entityParallax.uv;
        }
        float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].SampleLevel(euvCoord, entityLod);
        float3 baseAlbedo = srgbToLinear(entityTexel.rgb) * pr.tint.rgb;
        float3 albedo = material == MATERIAL_DIELECTRIC
                ? lerp(float3(1.0, 1.0, 1.0), baseAlbedo, entityTexel.a) : baseAlbedo;
        Surface surface = evaluateMaterial(header, euvCoord, entityLod, albedo, n,
                ep0, ep1, ep2, euv[e0], euv[e1], euv[e2], vdir,
                header.params.x, header.params.y, pr.normal.w, material == MATERIAL_OPAQUE);
        // A column side wall is real geometry the normal map knows nothing about: it wins over the
        // mapped normal, which is authored for the face the column stands on.
        n = entityParallax.wall ? entityParallax.normal : surface.normal;

        payload.albedo = half3(albedo * surface.ao);
        payload.normal = half3(n);
        payload.hitT = RayTCurrent();
        // Per-vertex motion vector: interpolate the captured per-vertex displacement with the same
        // indices/barycentrics used for the UV above. Rotation and skeletal/lid animation use a
        // per-vertex buffer; pure whole-object translation is packed into rigidDisp with no buffer.
        if (g.dispAddr != 0) {
            ConstPtr<float4> dd = ConstPtr<float4>(g.dispAddr);
            payload.motionPrev = half3(ebary.x * dd[e0].xyz + ebary.y * dd[e1].xyz + ebary.z * dd[e2].xyz);
        } else {
            payload.motionPrev = half3(g.rigidDisp.xyz);
        }
        payload.f0 = half3(surface.f0);
        float emission = material == MATERIAL_OPAQUE ? surface.emission : 0.0;
        float sss = material == MATERIAL_OPAQUE ? surface.sss : 0.0;
        payloadSetPacked(payload, material, surface.roughness, surface.metalness,
                emission, sss, header.params.z, header.params.w,
                materialEmissionSource(header, emission));
        payload.flags |= PAYLOAD_DYNAMIC_ENTITY;
        payloadSetDielectric(payload, material, entering);
        return;
    }

    Section sec = ConstPtr<Section>(pc.tableAddr)[InstanceID()];
    // Any-hit/SBT opt: the section BLAS has one fixed geometry per material bucket (solid / cutout /
    // translucent / water). PrimitiveIndex() restarts at 0 per geometry, so re-add this geometry's
    // triangle base to land in the section's packed prim/uv arrays (concatenated in BUCKET order).
    // Water prims keep tint.w == 1, converted to the packed material enum below.
    uint pid = PrimitiveIndex() + sec.triBase[GeometryIndex()];
    TerrainPrim pr = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
    MaterialHeader materialHeader = ConstPtr<MaterialHeader>(pc.materialTableAddr)[pr.materialId];
    float3 n = normalize(pr.normal.xyz);
    float3 tint = pr.tint.rgb;

    // Water prims are single-sided, wound outward from the fluid volume (RtFluidMesher), so the
    // UNFLIPPED geometric normal is a reliable "which side of the dielectric" signal: a ray travelling
    // against it is entering the volume, one travelling with it is exiting. Captured before the
    // toward-viewer flip below and carried in the payload so raygen derives the medium from hit
    // orientation instead of toggling parity per crossing (a single missing/extra face no longer
    // desyncs the rest of the path).
    bool entering = dot(WorldRayDirection(), n) < 0.0;

    // Water is a clear dielectric: biome tint + geometric normal, never the still/flow atlas sprite.
    // That 16×16 repeating square is the azulejo Animated Water replaces with a height field in raygen.
    if (materialHeader.model == MATERIAL_WATER) {
        if (dot(n, -WorldRayDirection()) < 0.0) n = -n;
        payload.albedo = half3(tint);
        payload.normal = half3(n);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(0.02h, 0.02h, 0.02h);
        payloadSetPacked(payload, MATERIAL_WATER, materialHeader.params.x, 0.0, 0.0, 0.0,
                materialHeader.params.z, materialHeader.params.w, EMISSION_SOURCE_NONE);
        payloadSetDielectric(payload, MATERIAL_WATER, entering);
        return;
    }

    // Lever B: per-triangle corner UVs in primitive order. uvs[3*pid + k] is a contiguous,
    // directly-addressed load (no index buffer, no scattered vertex-UV gather), so it issues as soon as
    // pid is known and its latency overlaps the prim fetch instead of serialising behind an index load.
    // The index buffer still exists for the BLAS build; the shading path just no longer reads it.
    ConstPtr<float2> uvs = ConstPtr<float2>(sec.uvAddr);
    float2 uv0 = uvs[3u * pid + 0u];
    float2 uv1 = uvs[3u * pid + 1u];
    float2 uv2 = uvs[3u * pid + 2u];
    float3 bary = float3(1.0 - attribs.x - attribs.y, attribs.x, attribs.y);
    float2 uv = bary.x * uv0 + bary.y * uv1 + bary.z * uv2;
    float3 tp0 = HitTriangleVertexPosition(0);
    float3 tp1 = HitTriangleVertexPosition(1);
    float3 tp2 = HitTriangleVertexPosition(2);
    float blockLod = rayConeTextureLod(rayCone, texSizePx(blockAlbedoAtlas), tp0, tp1, tp2, uv0, uv1, uv2);

    // Orient the GEOMETRIC normal toward the viewer FIRST (double-sided geometry), so the normal-map TBN
    // is built in the correct hemisphere. Doing this before the map — rather than flipping the perturbed
    // normal afterward — is what avoids the grazing-angle black spots: a post-flip could invert a tipped
    // mapped normal through the surface and light the back face.
    float3 vdir = -WorldRayDirection(); // toward the viewer
    if (dot(n, vdir) < 0.0) {
        n = -n;
    }

    // Parallax Occlusion Mapping: restrict relief to the opaque geometry bucket so leaves, plants,
    // grass overlays and translucent blocks cannot shade a different texel than the one that
    // established their any-hit coverage. Block sprites tile, so the height walk may wrap.
    ParallaxHit terrainParallax = parallaxFlat(uv, n);
    if (GeometryIndex() == BUCKET_SOLID && materialHeader.model == MATERIAL_OPAQUE) {
        terrainParallax = parallaxTrace(materialHeader, uv, blockLod, n, tp0, tp1, tp2,
                uv0, uv1, uv2, vdir, true);
        uv = terrainParallax.uv;
    }

    // Stained glass / ice: a thin colored filter resolved in raygen
    // (material 3). albedo carries the transmission tint = texel rgb mixed toward white by (1 − opacity),
    // so a more opaque texel tints transmitted light more strongly.
    if (materialHeader.model == MATERIAL_DIELECTRIC) {
        float4 gtex = blockAlbedoAtlas.SampleLevel(uv, blockLod);
        float3 gtexRgb = srgbToLinear(gtex.rgb);
        float3 glassAlbedo = lerp(float3(1.0, 1.0, 1.0), gtexRgb * tint, gtex.a);
        Surface glassSurface = evaluateMaterial(materialHeader, uv, blockLod, glassAlbedo, n,
                tp0, tp1, tp2, uv0, uv1, uv2, vdir,
                materialHeader.params.x, materialHeader.params.y, 0.0, false);
        n = glassSurface.normal;
        // Translucent blocks (glass, ice, …) are breakable too — apply the same overlay here, reusing
        // gtex (already sampled above) rather than re-fetching blockAlbedoAtlas.
        payload.albedo = half3(applyBreaking(glassAlbedo * glassSurface.ao,
                rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n));
        payload.normal = half3(n);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(glassSurface.f0);
        payloadSetPacked(payload, MATERIAL_DIELECTRIC, glassSurface.roughness,
                glassSurface.metalness, 0.0, 0.0, materialHeader.params.z,
                materialHeader.params.w, EMISSION_SOURCE_NONE);
        payloadSetDielectric(payload, MATERIAL_DIELECTRIC, entering);
        return;
    }

    // ---- Portals. RtTerrainMesher routes the nether/end portal sprites into the SOLID bucket and
    // tags their prims (TERRAIN_PRIM_PORTAL_*), so they never take the glass-dielectric path above:
    // the Nether portal is a self-lit animated swirl, the End portal shows the overworld's real
    // starfield over a pure-black void, view-parallax like vanilla's end-portal shader.
    uint terrainFlags = pr.flags;
    if ((terrainFlags & TERRAIN_PRIM_PORTAL_NETHER) != 0u) {
        WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
        float portalTime = worldPush.waterParams.w; // per-frame animation clock (seconds, wrapped)
        // Scroll inside the sprite's own rect (local 0..1, wrapped) so the drift can never bleed a
        // neighbouring atlas sprite in; the vanilla 32-frame atlas animation plays on top of it, so
        // the swirl is alive either way. LOD 0 keeps the swirl texels crisp at any distance.
        float2 localUv = (uv - materialHeader.albedoUv.xy) * materialHeader.albedoUv.zw;
        float2 scrolled = frac(localUv + float2(portalTime * NETHER_PORTAL_SCROLL_U,
                portalTime * NETHER_PORTAL_SCROLL_V));
        float2 swirlUv = materialHeader.albedoUv.xy + scrolled / materialHeader.albedoUv.zw;
        float4 swirlTexel = blockAlbedoAtlas.SampleLevel(swirlUv, 0.0);
        // Tint is the quad's authored colour (white for vanilla), never the glass lerp toward white.
        // The texture's own alpha darkens the gaps between the swirl arms instead of transmitting —
        // the portal is a surface, not a dielectric.
        float3 swirl = srgbToLinear(swirlTexel.rgb) * tint * (0.35 + 0.65 * swirlTexel.a);
        float swirlLum = luminance(swirl);
        payload.albedo = half3(swirl);
        payload.normal = half3(n);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(0.04h, 0.04h, 0.04h);
        payloadSetPacked(payload, MATERIAL_OPAQUE, 0.6, 0.0,
                swirlLum * NETHER_PORTAL_EMISSION, 0.0, 1.0, 0.0, EMISSION_SOURCE_UNIFORM);
        payloadSetDielectric(payload, MATERIAL_OPAQUE, entering);
        if ((terrainFlags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
            payload.flags |= PAYLOAD_EMITTER_IN_LIST;
        }
        return;
    }
    if ((terrainFlags & TERRAIN_PRIM_PORTAL_END) != 0u) {
        WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
        float portalTime = worldPush.waterParams.w;
        // The portal's starfield wheels with the world's celestial axis (same rotation world.rmiss
        // applies to the sky), so it stays locked to the real night sky's stars.
        float3 nightSky = endPortalNightSky(WorldRayDirection(), portalTime, worldPush.celestial);
        payload.albedo = half3(nightSky * END_PORTAL_BRIGHTNESS);
        payload.normal = half3(n);
        payload.hitT = RayTCurrent();
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        // No specular response: the portal is a pure self-lit surface, so it can never reflect a
        // light source (a torch's glint used to show up on it).
        payload.f0 = half3(0.0h, 0.0h, 0.0h);
        payloadSetPacked(payload, MATERIAL_OPAQUE, 0.9, 0.0,
                luminance(nightSky) * END_PORTAL_EMISSION, 0.0, 1.0, 0.0, EMISSION_SOURCE_UNIFORM);
        payloadSetDielectric(payload, MATERIAL_OPAQUE, entering);
        // Fully unlit: raygen adds only the portal's own emission and ends the path, so no scene
        // light (torch, sun, GI) can illuminate or reflect off the starfield.
        payload.flags |= PAYLOAD_END_PORTAL_UNLIT;
        if ((terrainFlags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
            payload.flags |= PAYLOAD_EMITTER_IN_LIST;
        }
        return;
    }

    payload.albedo = half3(srgbToLinear(blockAlbedoAtlas.SampleLevel(uv, blockLod).rgb) * tint);
    payload.hitT = RayTCurrent();
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h); // static terrain: camera-only motion vector

    // normal.w packs the 0..1 block-light level plus a +2 offset flag for non-SOLID (cutout /
    // translucent) render layers, set at extraction so the hit can opt SOLID terrain out of SSS below.
    float ew = pr.normal.w;
    bool nonSolid = ew >= 1.5;
    float emission = nonSolid ? ew - 2.0 : ew; // heuristic emission source (block light level)
    Surface surface = evaluateMaterial(materialHeader, uv, blockLod, float3(payload.albedo), n,
            tp0, tp1, tp2, uv0, uv1, uv2, vdir,
            materialHeader.params.x, materialHeader.params.y, emission, nonSolid);
    n = surface.normal;
    payload.albedo *= half(surface.ao);
    payload.albedo = half3(applyBreaking(float3(payload.albedo), rayCone, WorldRayOrigin(), WorldRayDirection(), RayTCurrent(), n));
    // Column side walls replace the mapped normal only after the decal, so the crack projection keeps
    // choosing its axis from the face the block is actually broken on.
    if (terrainParallax.wall) {
        n = terrainParallax.normal;
    }
    payload.normal = half3(n);
    payload.f0 = half3(surface.f0);
    payloadSetPacked(payload, MATERIAL_OPAQUE, surface.roughness, surface.metalness,
            surface.emission, surface.sss, materialHeader.params.z, materialHeader.params.w,
            materialEmissionSource(materialHeader, surface.emission));
    payloadSetDielectric(payload, MATERIAL_OPAQUE, entering);
    // RIS emitter-NEE membership: raygen gates this emitter's direct-hit emission term (RIS covers it).
    if ((pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER) != 0u) {
        payload.flags |= PAYLOAD_EMITTER_IN_LIST;
    }
}
