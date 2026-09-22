// Bindings, per-frame push state, the ray payload, and the constants every pass shares.
// Import first: everything below depends on pc, worldPush and payload.

import world_common;

[[vk::push_constant]] public WorldPushConstants pc;

[[vk::binding(0, 0)]] public RaytracingAccelerationStructure topLevelAS;
// HDR trace target: linear radiance, may exceed 1. Tonemap happens later at the display.comp seam.
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;
// Guide buffers — first-hit (primary-visibility) attributes consumed by the denoiser/DLSS-RR. Written
// every frame regardless of accumulation. gNormal.w carries LINEAR roughness (= GGX alpha, which is what
// DLSS-RR documents for this input); gDepth carries HW reversed-Z depth.
[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNormal;     // xyz world normal, w linear roughness
[[vk::binding(4, 0)]] [format("rgba16f")] public RWTexture2D<float4> gAlbedo;     // rgb diffuse albedo
[[vk::binding(5, 0)]] [format("r32f")]    public RWTexture2D<float>  gDepth;      // HW reversed-Z depth
[[vk::binding(6, 0)]] [format("rg16f")]   public RWTexture2D<float2> gMotion;     // screen-space motion (render px)
[[vk::binding(7, 0)]] [format("rgba16f")] public RWTexture2D<float4> gSpecAlbedo; // exact-mirror reflected diffuse × reflectance; material reflectance otherwise
[[vk::binding(8, 0)]] [format("rg16f")]   public RWTexture2D<float2> gSpecMotion; // reflection MVs for RR
// Denoiser signals: gViewZ in Pass A (NRD's IN_VIEWZ, and the guide SVGF validates its reprojection
// against, so it is written whenever EITHER denoiser is active), plus the YCoCg-packed per-lobe
// radiance + normalized hit distance in Pass B (NRD only). Formats are NRD's contract (IN_VIEWZ =
// R16f+; IN_*_RADIANCE_HITDIST = RGBA16f with the REBLUR pack); the normal + roughness input is
// gNormal itself (NRD is built with the RGBA16/SNORM normal encoding + LINEAR roughness, whose
// unpack is exactly xyz + w — no repack needed).
[[vk::binding(9, 0)]]  [format("r32f")]    public RWTexture2D<float>  gViewZ;    // linear view depth, primary hit (sky = large)
[[vk::binding(10, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNrdDiff;  // YCoCg diffuse radiance, w = normalized hit distance
[[vk::binding(11, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNrdSpec;  // YCoCg specular radiance, w = normalized hit distance

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `worldPush` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
public static WorldPush worldPush = {};

// The radiance payload remains module-level so tracePath and its guide helpers can share it across
// HitObject trace/invoke calls, mirroring the GLSL rayPayloadEXT global.
public static Payload payload = {};

public struct VisibilityResult {
    public float3 transmittance;
    public float waterHitT;
};



public uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
// Set by world.rchit on any dielectric hit (see PAYLOAD_DIELECTRIC_ENTERING) — whether the ray was
// travelling into the volume (vs. out of it) at this hit's face.
public bool payloadDielectricEntering() { return (payload.flags & PAYLOAD_DIELECTRIC_ENTERING) != 0u; }
public bool payloadEmitterInList() { return (payload.flags & PAYLOAD_EMITTER_IN_LIST) != 0u; }
public bool payloadDynamicEntity() { return (payload.flags & PAYLOAD_DYNAMIC_ENTITY) != 0u; }
// LINEAR roughness, i.e. GGX alpha directly — NOT perceptual roughness. This is the one convention used
// end to end: LabPBR defines roughness = (1 - perceptualSmoothness)^2 and RtLabPbr.decode stores exactly
// that, RtMaterials.Profile carries the same units, and DLSS-RR wants linear roughness in its guide. So
// nothing squares it: ggxD/ggxG1/sampleGGXVNDF/rrSpecularAlbedo all take this value as alpha as-is, and
// any threshold compared against it is an alpha threshold.
public float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
public float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }

// Metallic Shininess is an optional polish pass, not an emission/bloom boost. It blends in only
// where the resource pack authored metalness, keeping dielectric and partially-metallic materials
// physically sensible. The 15% floor gives a strongly polished look without turning every metal into
// a perfectly sharp, noisy delta mirror.
public float metallicShininessRoughness(float roughness, float metalness) {
    float polish = saturate(worldPush.materialParams.x) * saturate(metalness);
    return clamp(roughness * lerp(1.0, 0.15, polish), 0.0, 1.0);
}
public float payloadEmission() { return unpackHalf2(payload.emissionSss).x; }
public float payloadSss() { return unpackHalf2(payload.emissionSss).y; }
public float payloadIor() { return unpackHalf2(payload.iorTransmission).x; }
public float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }

// ---- Per-frame feature toggles + dimension, resolved once on the CPU (CausticaConfig -> WorldPush).
// Reading them through these helpers rather than open-coding the bit test keeps every pass agreeing on
// what a flag means, and gives one place to look when a new option is added.
public bool sssEnabled() { return worldFeature(worldPush, FEATURE_SSS); }
public bool denoiserEnabled() { return worldFeature(worldPush, FEATURE_DENOISER); }
public bool nrdEnabled() { return worldFeature(worldPush, FEATURE_NRD); }
public bool viewZEnabled() { return worldFeature(worldPush, FEATURE_VIEWZ); }
public bool restirEnabled() {
    // Use the explicit hot push uniform as the authoritative live toggle. The featureFlags copy remains
    // useful to diagnostics, but must not be able to leave the shading branch stuck on the RIS fallback.
    return pc.restirMode != 0u
            && pc.restirPreviousAddr != 0 && pc.restirCurrentAddr != 0;
}
public bool waterWavesEnabled() { return worldFlag(worldPush, WORLD_FLAG_WAVES); }
public bool sharcEnabled() { return worldFeature(worldPush, FEATURE_SHARC); }
public bool cameraSubmerged() { return worldFlag(worldPush, WORLD_FLAG_SUBMERGED); }

/** SSS strength after the player toggle: the material value, or a hard zero when SSS is disabled. */
public float payloadActiveSss() { return sssEnabled() ? payloadSss() : 0.0; }

// Firefly ceiling for the UNDENOISED path. With RR running, a rare very bright sample is exactly the
// energy the temporal filter is built to absorb, and clamping it would darken the image. With RR off
// there is no accumulation at all, so that same sample is a permanent white speck in every frame — the
// single worst artefact of the raw reference view at low SPP. Applied only when the denoiser is off,
// which keeps the denoised path bit-identical to before this option existed.
public static const float UNDENOISED_FIREFLY_MAX_LUMINANCE = 24.0;
/**
 * Firefly bound for the per-lobe signals handed to NRD, as a MULTIPLE of the pixel's own combined
 * radiance rather than an absolute luminance. An absolute cap cannot work across a scene that spans
 * full sun and deep shade: at a shadow level of ~0.05 a sample at luminance 5 is a 100x outlier yet
 * sits far below any cap chosen to leave sunlit highlights alone. The floor keeps the bound usable
 * where the combined radiance is itself near zero.
 */
public static const float NRD_FIREFLY_RELATIVE_MAX = 8.0;
public static const float NRD_FIREFLY_FLOOR = 0.02;

public static const float PI = 3.14159265359;
public static const float INV_PI = 0.31830988618;

// The analytic sky (gradient + sun/moon discs + stars) is computed in world.rmiss. On a miss, raygen
// reads payload.albedo and accumulates that sky radiance. The directional NEE light is pushed separately
// as worldPush.lightDir / worldPush.lightRadiance / worldPush.sunDir.
public static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
public static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
public static const float WATER_IOR = 1.333;
public static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
public static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Transmitted-ray origin offset when crossing a face from the TRANSLUCENT terrain layer (glass, ice).
// Must be SMALLER than the mesher-side TRANSLUCENT_INSET (RtTerrainMesher, 2e-4 blocks): those quads are
// recessed into their block by that inset, so a face touching a slab/stair leaves a tiny gap before the
// neighbour's surface. A large bias (SURF_BIAS) would restart the ray past that neighbour and see
// through it; this lands the ray inside the gap so the neighbour is still hit, while still clearing the
// surface it left to avoid self-intersection. Water is meshed by RtFluidMesher with no inset, so it uses
// the ordinary bias.
public static const float INSET_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
public static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
public static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
public static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
public static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

// Stored roughness IS alpha (see payloadRoughness), so these compare against it directly.
public static const float MIRROR_ALPHA_MAX = 4.0e-4; // LabPBR perceptual smoothness >= 0.98
// Above this the specular lobe is too broad for a single mirrored reprojection to describe, so the
// reflection MV is left at zero and RR falls back to the ordinary MV.
public static const float SPEC_MOTION_ALPHA_MAX = 0.25; // perceptual smoothness <= 0.5

public bool isDeltaAlpha(float roughness) {
    return clamp(roughness, 0.0, 1.0) <= MIRROR_ALPHA_MAX;
}

// ---- NRD (REBLUR) front-end pack helpers. These MUST stay in sync with NRD.hlsli's front-end
// functions — NRD decodes with the matching back-end math, and a drift here silently corrupts the
// denoised image. The hit-distance normalization constants are REBLUR's default
// ReblurHitDistanceParameters (A = 3, B = 0.1, C = 20) on the host side; if the host ever tunes
// them, these must move with them.
public float3 nrdLinearToYCoCg(float3 color) {
    float Y  = dot(color, float3(0.25, 0.5, 0.25));
    float Co = dot(color, float3(0.5, 0.0, -0.5));
    float Cg = dot(color, float3(-0.25, 0.5, -0.25));
    return float3(Y, Co, Cg);
}

// "spec magic curve" from NRD.hlsli (_NRD_GetSpecMagicCurve with power = 0.5).
public float nrdSpecMagicCurve(float roughness) {
    float f = 1.0 - exp2(-200.0 * roughness * roughness);
    return f * pow(saturate(roughness), 0.5);
}

// REBLUR_FrontEnd_GetNormHitDist with the default (A, B, C): normalizes the raw hit distance into
// (0, 1] using the view depth and roughness. Call ONLY when the lobe was actually traced — 0 stays
// reserved for "lobe skipped / no data", which is how REBLUR detects probabilistic selection.
public float nrdNormHitDist(float hitDist, float viewZ, float roughness) {
    float f = (3.0 + abs(viewZ) * 0.1) * lerp(20.0, 1.0, nrdSpecMagicCurve(roughness));
    return max(saturate(hitDist / f), 1.0e-4);
}

// ---- NRD material demodulation (NRD.hlsli's NRD_MaterialFactors).
//
// NRD denoises pure RADIANCE, not irradiance with the surface material baked in: "materials need to
// be decoupled from the input signal ... BRDF should be applied AFTER denoising". Feeding it
// modulated radiance makes its spatial kernels average across texture detail, so denoising bleeds
// albedo across block edges — a large part of what previously looked like temporal smearing.
// The tracer divides the per-lobe radiance by these factors and the combine pass multiplies them
// back, so the material detail passes through the denoiser untouched.
//
// IMPORTANT: the factors are derived ONLY from guide textures that both sides can read
// (gAlbedo.rgb, gSpecAlbedo.rgb, gNormal.w), never from values that live inside the tracer. That is
// what makes the round trip exact: nrd_combine.comp mirrors this function bit for bit, so whatever
// is divided out here is multiplied back there, even if the material model changes later.
//
// The floors are NRD's own NRD_MATERIAL_FACTOR_MIN_SCALE / NRD_ROUGHNESS_FACTOR_MIN_SCALE; they are
// what stops a near-black albedo from turning the division into an explosion of noise (dividing a
// noisy signal by ~0 is unbounded amplification, and NRD warns that smaller values are unstable).
public static const float NRD_MATERIAL_FACTOR_MIN_SCALE = 0.02;
public static const float NRD_ROUGHNESS_FACTOR_MIN_SCALE = 0.1;

/**
 * Diffuse/specular demodulation factors for a primary hit. {@code specAlbedo} is the renderer's
 * existing pre-integrated specular albedo guide (gSpecAlbedo) — the same quantity DLSS-RR consumes
 * for exactly this purpose, i.e. NRD's "Fenv" — and {@code roughness} is LINEAR roughness.
 */
public void nrdMaterialFactors(float3 albedo, float3 specAlbedo, float roughness,
                               out float3 diffFactor, out float3 specFactor) {
    float3 fenv = saturate(specAlbedo);
    diffFactor = lerp(float3(NRD_MATERIAL_FACTOR_MIN_SCALE), float3(1.0),
            saturate((1.0 - fenv) * saturate(albedo)));
    // "Don't be greedy": NRD scales the specular factor down toward the floor at low roughness,
    // where a mirror-like lobe carries almost all of its energy in the reflected image itself.
    specFactor = fenv * lerp(float3(NRD_ROUGHNESS_FACTOR_MIN_SCALE), float3(1.0), saturate(roughness));
    specFactor = lerp(float3(NRD_MATERIAL_FACTOR_MIN_SCALE), float3(1.0), saturate(specFactor));
}

// Continuations one sample may carry: the camera segment, plus the reflection branch a primary
// dielectric splits off. Raising this needs a real queue, which is what the continuation buffer is for.
public static const uint MAX_PATH_SEGMENTS = 2u;
