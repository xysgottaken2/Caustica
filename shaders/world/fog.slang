// Volumetric fog: a participating-air term integrated over each path SEGMENT, lit by the
// celestial body and — through the caller's emitter samples — by the block lights themselves.
// No world-space grid, no per-light splatting, no CPU upload: the entire medium is the four
// lanes of worldPush.fogParams, and the only new state is the shadow rays this file borrows.
//
// WHY THE SEGMENT AND NOT A VOXEL GRID. A fog volume stored in world space answers "what does
// the camera ray see" and nothing else: reflections, refractions and indirect bounces either
// miss the fog entirely or need the same march re-run per ray, and light injected into voxels
// rescales the frame cost with the light count. Inside the path integral, fog is a property of
// the ray's travel between two hits, so every ray kind gets it from one code path, and the two
// expensive questions — transmittance along the segment (the medium block in world.rgen already
// applies Beer-Lambert per segment) and "what occludes the light here" (the shadow rays the
// renderer already casts for surface shading) — are answered with data the pass holds anyway.
// This is the Quake II RTX / Frostbite approach: closed-form Beer-Lambert per segment with a
// handful of light samples, and temporal accumulation in place of march density.
//
// LEAKS ARE WHAT THE SHADOW SAMPLE IS FOR. There is deliberately no leak-prevention structure
// (no sealed-room detection, no heightmap of sky, no occupancy grid): the in-scatter sample
// point fires one ordinary visibility() ray toward the sun through the real BVH, so cave air,
// the far side of a mountain and the shade of a cloud each get zero sun in-scatter from the same
// test the ground uses — fog can never disagree with a surface shadow, and torch cones are
// occluded by the same geometry that occludes the torch's light on the receiver.
//
// QUALITY TIERS. The full tier (rays the player looks along: camera and specular bounces, i.e.
// showCelestial) pays one jittered sun-shadow sample per segment plus, at emitter-shaded
// receivers, one short ray for the fog's own view of the winning light. The cheap tier (diffuse
// continuations) keeps only the Beer-Lambert darkening and NO in-scatter. Unoccluded in-scatter
// at bounce depth would re-light cave air that the primary ray correctly found dark — a leak
// wearing the disguise of a performance option — while pure transmittance at bounces is what
// the denoised GI cannot resolve differently anyway.
//
// Like clouds.slang this module takes the WorldPush explicitly at every entry point. Unlike
// clouds.slang it is imported by the raygen passes only (never by world.rmiss — the sky/miss
// shading composites fog in the RAYGEN's miss branch instead), so it may import world_core
// (VisibilityResult), trace, medium and clouds freely — Slang imports are not transitive for
// name lookup, so the visibility result type must be imported where it is spelled.

import world_common;
import world_core;
import math;
import trace;
import medium;
import clouds;

// ---- Tuning. Sliders ride worldPush.fogParams: x = density fraction, y = HG anisotropy,
// z = integration distance (blocks), w = height falloff scale (blocks; 0 = uniform).

// Per-block scattering cross-section at density slider 1. Fog is defined by its SCATTERING;
// extinction (the darkening) is derived from it via the albedo, so one slider tunes the look
// without letting brightness and visibility drift apart. At 0.012 a 100-block view transmits
// exp(-1.2 * 0.95) ~= 32% — strong but not milk.
public static const float FOG_SIGMA_MAX = 0.012;
// Scattering albedo: real fog scatters far more than it absorbs; absorption stays a small
// fraction so dense fog dims without going soot-black.
public static const float FOG_ALBEDO = 0.95;
public static const float FOG_EXTINCTION_SCALE = 1.0 / FOG_ALBEDO;
// Neutral cool-white scatter colour (Mie scattering of water fog is nearly hueless; the faint
// blue is the same Rayleigh bias vanilla's own fog renders).
public static const float3 FOG_TINT = float3(0.84, 0.90, 1.0);
// Fallback integration distance if the push lane is left zero.
public static const float FOG_DEFAULT_FAR = 160.0;
// World Y the fog density plateaus AT when the height profile is on: everything below stays at
// full slider density, everything above decays exp(-dy / H). Pools mist into valleys and lets
// mountain tops and flight clear it, with no occlusion structure to maintain.
public static const float FOG_HEIGHT_BASE_Y = 90.0;
// Quadrature steps used for the height profile, full tier / cheap tier (see the cloud march
// budget for the same split). The uniform case needs no steps at all — closed form.
public static const int   FOG_PROFILE_STEPS = 6;
public static const int   FOG_PROFILE_STEPS_CHEAP = 2;
// The sun-occlusion sample is taken at a stratified point FOG_SAMPLE_MIN..(MIN+SPAN) along the
// segment. Not 0..1: an endpoint sample degenerates to "the receiver's own shadow test" at
// d -> 0 and double-uses the surface's contact, where self-intersection bias lives.
public static const float FOG_SAMPLE_MIN = 0.12;
public static const float FOG_SAMPLE_SPAN = 0.76;
// Hue-preserving firefly ceiling for the emitter term, matching the reservoir shading budget.
public static const float FOG_MAX_SCATTER_LUMINANCE = 16.0;
// Weather coupling. Rain is the one weather the fog cannot learn from geometry — the sky's
// overcast is resolved on the CPU — so it rides worldPush.weather, the same single-source lane
// the cloud rework established ("a storm is a STATE, not an occlusion lottery"), behind the
// same feature flag the sky gates on. Rain thickens the medium (wet air holds more of it, and
// one coefficient keeps darkening and glow in lockstep); and because the weather already erases
// the sun disc for the downpour, the celestial shafts dim by the same lane rather than draw
// god rays punching full-bright through rain the sky says is closed.
public static const float FOG_RAIN_DENSITY_GAIN = 1.0;
public static const float FOG_RAIN_SUN_DIM = 0.75;

public float fogRain(WorldPush push) {
    return worldFeature(push, FEATURE_WEATHER_LIGHTING) ? saturate(push.weather.x) : 0.0;
}

// Biome/weather tint: xyz is vanilla's own FOG_COLOR camera attribute, resolved by the game at
// the camera — biome blend, weather, dimension, all composed by the attribute system this
// renderer already trusts for the sun angle and the cloud colour. w is the player's blend
// strength, so "off" is a constant lerp, not a special case. Both scatter sites (celestial and
// emitter) read it through this one function: a shaft and a torch cone can never disagree about
// what colour the air is, because they ask the same question the same way.
public float3 fogTintNow(WorldPush push) {
    return lerp(FOG_TINT, push.fogTint.xyz, saturate(push.fogTint.w));
}

public struct FogVolume {
    public float3 scatter;       // premultiplied in-scattered radiance along the segment
    public float transmittance;  // what the segment's background keeps
};

public bool fogEnabled(WorldPush push) {
    return push.fogParams.x > 0.0;
}

// Fog is the AIR medium. A segment that lies inside water or a volume dielectric already carries
// that medium's own extinction (the Beer-Lambert block in world.rgen applies it to the path);
// stacking the air fog on top of it would attenuate the same stretch twice and re-brighten it
// with sunlight the water has already absorbed. Air segments have zero medium extinction, so
// this is one max() against zero on the ordinary path.
public bool fogAppliesHere(MediumStack stack) {
    return max(stack.current.extinction.x, max(stack.current.extinction.y,
            stack.current.extinction.z)) <= 0.0;
}

public float fogFarBlocks(WorldPush push) {
    return push.fogParams.z > 0.0 ? push.fogParams.z : FOG_DEFAULT_FAR;
}

// Density profile multiplier in [0,1]. `yBlock` is the block-space height the path shaders
// already use; the terrain origin from sharcGridOrigin turns it into world Y even when the SHaRC
// cache itself is off (its lanes are pushed unconditionally).
public float fogHeightFactor(WorldPush push, float yBlock) {
    float H = push.fogParams.w;
    if (H <= 0.0) {
        return 1.0;
    }
    float yWorld = yBlock + float(push.sharcGridOrigin.y);
    return exp(-max(0.0, yWorld - FOG_HEIGHT_BASE_Y) / H);
}

// ---- Celestial in-scatter over one segment.
//
// Returns the same {scatter, transmittance} pair the cloud block composites with, and is applied
// the same way: scatter at the segment's entry throughput, then the transmittance darkens
// whatever the path finds further on. Origin is block-space (the space hitPos/ro live in).
//
// The uniform case is a closed form — zero steps, zero rays beyond the one occlusion sample:
//   T(d) = exp(-sigma_t d),  ∫₀ᵈ T = (1 - T(d)) / sigma_t.
// With a height profile the integral degrades to a front-to-back midpoint quadrature, stepping
// the transmittance like cloudMarch does, because a straight segment across an exponential
// gradient is exactly the case a single closed-form band would smear.
public FogVolume fogSegment(WorldPush push, float3 originBlock, float3 dir, float maxDistance,
                            inout uint seed, bool highQuality) {
    FogVolume v;
    v.scatter = float3(0.0, 0.0, 0.0);
    v.transmittance = 1.0;
    float sigmaS = push.fogParams.x * FOG_SIGMA_MAX;
    float rain = fogRain(push);
    sigmaS *= 1.0 + FOG_RAIN_DENSITY_GAIN * rain;
    float d = min(maxDistance, fogFarBlocks(push));
    float3 sun = push.lightRadiance.xyz;
    // Degenerate segment, fog off, or no celestial light to scatter (the Nether and the End
    // push a zeroed lightRadiance, so the whole term folds to this comparison there — the same
    // gate that skips the sun NEE, kept deliberately so fog and sunlight can never disagree).
    if (sigmaS <= 0.0 || d <= 0.0 || max(sun.x, max(sun.y, sun.z)) <= 0.0) {
        return v;
    }
    float sigmaT = sigmaS * FOG_EXTINCTION_SCALE;

    // The god-ray march, at its cheapest honest size: one shadow sample at a stratified point
    // along the segment per full-tier ray, accumulating into the TAA history over frames.
    //
    // lightTerm exists ONLY on the full tier, and that is not an optimisation: in-scatter is
    // useless without its occlusion test. A cheap-tier segment that scattered the unshadowed
    // sun would paint full daylight into cave air the primary ray correctly found dark — every
    // diffuse bounce adding the entire celestial column. The cheap tier is therefore pure
    // Beer-Lambert darkening, exactly as the QUALITY TIERS header promises; v.scatter stays 0
    // and callers folding `L += throughput * v.scatter` become no-ops there for free.
    float3 lightTerm = float3(0.0, 0.0, 0.0);
    if (highQuality) {
        float3 lightDir = push.lightDir.xyz;
        // Forward peak toward the sun, same convention as the SSS term: the ray direction and the
        // light direction align when the eye looks INTO the light, and the shafts open up there.
        // Unjittered: the shaft edge stays temporally rock-stable while the surface penumbra
        // softens from its own quad sampling — a jittered fog edge against a soft surface shadow
        // is how "cone lit but ground dark" flicker starts.
        float phase = hg(saturate(dot(dir, lightDir)), push.fogParams.y);
        float tS = d * (FOG_SAMPLE_MIN + rndf(seed) * FOG_SAMPLE_SPAN);
        float3 sp = originBlock + dir * tS;
        VisibilityResult shadow = visibility(sp, lightDir, 10000.0);
        float3 sunVis = shadow.transmittance;
        sunVis *= cloudSunShadow(push, sp - push.camOffset, lightDir);
        // Storm is a STATE: the weather lane that erases the sun disc also dims the shafts, so
        // the fog never argues with the sky about whether the sun is out. Geometry (the shadow
        // ray above) stays the only occlusion authority for clear-weather clouds and mountains.
        sunVis *= 1.0 - FOG_RAIN_SUN_DIM * rain;
        lightTerm = fogTintNow(push) * phase * sun * sunVis;
    }

    if (push.fogParams.w <= 0.0) {
        float dTau = sigmaT * d;
        float T = exp(-dTau);
        v.transmittance = T;
        if (highQuality) {
            float I = dTau < 1.0e-4 ? d : (1.0 - T) / sigmaT;
            v.scatter = lightTerm * (sigmaS * I);
        }
        return v;
    }
    // Cheap tier with a height profile: still one transmittance march, but at the cheap step
    // count and with the zero lightTerm it accumulates nothing — the loop is the darkening,
    // nothing else.
    int steps = highQuality ? FOG_PROFILE_STEPS : FOG_PROFILE_STEPS_CHEAP;
    float stepLen = d / float(steps);
    float T = 1.0;
    float3 acc = float3(0.0, 0.0, 0.0);
    for (int i = 0; i < steps; i++) {
        float t = (float(i) + 0.5) * stepLen;
        float dens = fogHeightFactor(push, originBlock.y + dir.y * t);
        acc += lightTerm * (sigmaS * dens * stepLen * T);
        T *= exp(-sigmaT * dens * stepLen);
    }
    v.scatter = acc;
    v.transmittance = T;
    return v;
}

// ---- Emitter in-scatter over one segment, sampled from the caller's already-chosen light.
//
// The caller hands over the SAME reservoir winner its surface shading uses (position, normal,
// radiance, area), so the fog never re-runs light selection: with five hundred torches lit, the
// cost per segment is still one quadrature and one short shadow ray, whatever the light count —
// the RIS reservoir already shrank "all lights" to "the one that matters here".
//
// The visibility test is fired from the FOG's sample point, not from the receiver: a wall
// between the flame and the middle of the segment has to block the cone even when the far end
// sees the light, and vice versa. That single choice is what removes the whole leak family.
public float3 fogEmitterScatter(WorldPush push, float3 originBlock, float3 dir, float segLen,
                                float3 lightPos, float3 lightNormal, float3 le, float area) {
    float sigmaS = push.fogParams.x * FOG_SIGMA_MAX;
    // Same rain gain as the celestial term: the cone travels through the thickened wet medium,
    // so its self-absorption and its visible reach change together — one coefficient, no
    // second knob for Java and the shader to disagree about.
    sigmaS *= 1.0 + FOG_RAIN_DENSITY_GAIN * fogRain(push);
    float d = min(segLen, fogFarBlocks(push));
    if (sigmaS <= 0.0 || d <= 0.0 || max(le.x, max(le.y, le.z)) <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // Quadrature point: where the segment passes closest to the light, clamped onto the segment.
    float s = clamp(dot(lightPos - originBlock, dir), 0.0, d);
    float3 sp = originBlock + dir * s;
    float3 toL = lightPos - sp;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-4) {
        return float3(0.0, 0.0, 0.0); // grazing the flame: no finite scattering to resolve there
    }
    float dist = sqrt(dist2);
    float3 wi = toL / dist;
    float cosL = max(0.0, dot(lightNormal, -wi));
    if (cosL <= 0.0) {
        return float3(0.0, 0.0, 0.0); // the fog sees the emitter's back face
    }
    // Stop just short of the emitter's own face, exactly like shadeReservoir does for the
    // receiver — and only then trust the ray: a blocked sample has no cone to draw.
    VisibilityResult shadow = visibility(sp, wi, max(dist * 0.999, 1.0e-3));
    float3 vis = shadow.transmittance;
    if (max(vis.x, max(vis.y, vis.z)) <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // Area-light geometry term in the same shape as the surface transport's (G * area), softened
    // by the emitter footprint so a path that grazes the quad stays finite.
    float geom = cosL * area / (dist2 + max(0.25 * area, 1.0e-3));
    float profile = fogHeightFactor(push, sp.y);
    float sigmaT = sigmaS * FOG_EXTINCTION_SCALE * profile;
    // Camera -> sample and sample -> light legs, then the closed-form segment weight — the
    // single-sample Beer integral (1 - e^-sigma_t d) / sigma_t, the same expression the
    // celestial term uses, evaluated at the sample's local density.
    float pathT = exp(-sigmaT * (s + dist));
    float dTau = sigmaT * d;
    float I = dTau < 1.0e-4 ? d : (1.0 - exp(-dTau)) / sigmaT;
    float phase = hg(saturate(dot(dir, wi)), push.fogParams.y);
    float3 scatter = fogTintNow(push) * (sigmaS * profile) * phase * le * geom * vis * I * pathT;
    float lum = luminance(scatter);
    if (lum > FOG_MAX_SCATTER_LUMINANCE) {
        scatter *= FOG_MAX_SCATTER_LUMINANCE / lum;
    }
    return scatter;
}
