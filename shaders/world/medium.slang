// Participating media: the tint-to-extinction mappings and the depth-2 medium stack the dielectric
// interface pushes and pops. Depends on core only.

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in payload.albedo for
// water hits, or worldPush.waterParams.xyz for the camera's own biome when starting submerged). A blue ocean
// tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.

import world_common;
import world_core;

public static const float WATER_DENSITY = 0.1;
public static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
// Extra per-block extinction the water-opacity option adds at its maximum (slider 1). At 4.0 one
// block of water transmits exp(-4) ~= 1.8% - effectively an opaque dark mirror; the low end of the
// slider gives gentle per-block dimming (exp(-0.4) ~= 67% at 0.1).
public static const float WATER_OPACITY_EXTINCTION_MAX = 4.0;
public float3 waterExtinctionBase(float3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

public float3 waterExtinction(float3 tint) {
    float3 base = waterExtinctionBase(tint);
    // When the eye is inside water, keep the medium's own extinction at its natural
    // clarity. The opacity slider is meant to dim the transmitted lobe seen THROUGH
    // water from the outside (fixing the DLSS-RR halo), not to make the water you
    // are swimming in opaque black. At 100% the extra is 4.0 per block -> exp(-40)
    // over 10 blocks would be ~4e-18, i.e. a black veil when submerged.
    if (cameraSubmerged()) {
        return base;
    }
    float extra = worldPush.waterOpacity.x * WATER_OPACITY_EXTINCTION_MAX;
    return base + float3(extra, extra, extra);
}

// A non-water dielectric's tint is a filter over one block of travel rather than a biome water colour, so
// it maps to extinction by inverting Beer-Lambert over that reference distance: transmittance t after one
// block means sigma = -ln(t). `transmission` scales that transmittance, so it keeps the sense the
// material's authored factor has everywhere else — 1 passes the tint through unchanged, 0 is opaque.
// Clamped away from zero so a fully saturated texel stays finite instead of producing an infinite sigma.
public static const float VOLUME_TINT_REFERENCE_DISTANCE = 1.0; // blocks
public float3 volumeExtinction(float3 tint, float transmission) {
    float3 blockTransmittance = clamp(clamp(tint, 0.0, 1.0) * clamp(transmission, 0.0, 1.0),
            1.0e-3, 1.0);
    return -log(blockTransmittance) / VOLUME_TINT_REFERENCE_DISTANCE;
}

// ---- Participating medium the path is currently inside.
//
// Refraction needs the RELATIVE index across an interface, so the renderer has to know what it is
// travelling through, not just what it is hitting. `ior` drives Snell/Fresnel; `extinction` drives the
// per-segment Beer-Lambert attenuation.
//
// The stack is depth 2 (current + the one it will return to) held in named fields, NOT an array. A
// dynamically indexed local array lands in scratch memory, and this raygen is already register-bound —
// paying an occupancy hit for nesting that Minecraft does not produce would be a bad trade. Depth 2
// covers air->water->glass and air->glass->water, which is the realistic worst case; anything deeper
// degrades to air on the way out, and because `entering` is re-derived per face from geometry rather
// than toggled, the path re-synchronises at the next crossing instead of staying corrupted.
public struct Medium {
    public float ior;
    public float3 extinction;
    public bool water; // drives the wave-refraction caustic on submerged receivers; nothing else is water-specific
};

public struct MediumStack {
    public Medium current;
    public Medium outer;
};

public Medium airMedium() {
    Medium m;
    m.ior = 1.0;
    m.extinction = float3(0.0, 0.0, 0.0);
    m.water = false;
    return m;
}

public MediumStack makeMediumStack(Medium start) {
    MediumStack s;
    s.current = start;
    s.outer = airMedium();
    return s;
}

public void mediumPush(inout MediumStack stack, Medium entered) {
    stack.outer = stack.current;
    stack.current = entered;
}

public void mediumPop(inout MediumStack stack) {
    stack.current = stack.outer;
    stack.outer = airMedium();
}

// Water's tint is a biome colour whose absorption is calibrated per block of depth; any other volume
// dielectric's tint is a filter over a reference block of travel. Both end up as per-channel extinction.
public Medium makeDielectricMedium(bool isWater, float ior, float3 tint, float transmission) {
    Medium m;
    m.ior = ior;
    m.extinction = isWater ? waterExtinction(tint) : volumeExtinction(tint, transmission);
    m.water = isWater;
    return m;
}
