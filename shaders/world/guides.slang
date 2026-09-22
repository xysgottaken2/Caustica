// DLSS-RR guide state and everything that resolves it: the gv_* captures, the foreground specular
// surface and its reflection motion vector, and the image writes.
// PRIMARY PASS ONLY. Depends on trace, water and segment.

// Primary-visibility guide attributes. Pass A traces exactly one sample per pixel; a split reflection
// leaf supplies the spec endpoint and its transmission leaf may replace the ordinary tuple.

import world_common;
import world_core;
import math;
import medium;
import water;
import trace;

public static float3 gv_normal = float3(0.0, 0.0, 0.0);
public static float3 gv_albedo = float3(0.0, 0.0, 0.0);
public static float gv_rough = 0.0;
public static float3 gv_hitCamRel = float3(0.0, 0.0, 0.0);       // primary-surface hit position relative to the current camera
public static bool gv_motionUseRefracted = false; // true when the MV tracks the refracted hit (its own reprojection delta)
// World-space displacement of the motion-guide surface since the previous frame (0 for static
// terrain/sky, an entity's per-vertex/object motion for a dynamic hit).
public static float3 gv_motionObjDisp = float3(0.0, 0.0, 0.0);
// Transmission replaces the ordinary guide tuple with the visible destination, so RR's specular inputs
// (specular albedo + reflection MV) need the foreground glass/water interface kept separately. This is
// the surface the reflection physically lives on; for an opaque primary hit it is that hit itself.
public struct SpecSurface {
    public float3 camRel;         // interface position relative to the current camera
    public float3 normal;         // FP32: reflection reprojection amplifies normal quantization at distance
    public float3 previousNormal; // the same normal one frame ago; equal to `normal` unless the surface animates
    public float3 biasNormal;     // geometric normal, used only to offset a ray origin off the surface
    public float3 motionPrev;     // current-minus-previous displacement of the reflecting surface
    public float roughness;
    public float3 albedo;         // specular albedo fed to DLSS-RR for demodulation (0 = pure diffuse)
};
public static SpecSurface gv_spec = {};

// Static surfaces reuse one normal for all three roles. Water overrides them (height-field shading
// point, waved normal now and last frame, flat geometric normal for the bias) via the six-argument form.
public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 previousNormal,
                            float3 biasNormal, float3 motionPrev, float roughness, float3 albedo) {
    SpecSurface s;
    s.camRel = camRel;
    s.normal = normal;
    s.previousNormal = previousNormal;
    s.biasNormal = biasNormal;
    s.motionPrev = motionPrev;
    s.roughness = roughness;
    s.albedo = albedo;
    return s;
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal,
            float3(0.0, 0.0, 0.0), roughness, albedo);
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 motionPrev,
                            float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal, motionPrev, roughness, albedo);
}
// angle can land behind the eye even though the reflector itself is comfortably in view.
public float2 projectPrevNdc(float3 worldPos, out bool valid) {
    float4 clip = mul(worldPush.prevViewProj, float4(worldPos - worldPush.camOffset + worldPush.camDelta, 1.0));
    valid = clip.w > 0.0;
    return valid ? clip.xy / clip.w : float2(0.0, 0.0);
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P seen
// in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye sees V
// along a straight line, so the reflection appears at proj(V). Project the mirror image directly.
// Gotcha: intersecting a reflection ray with the surface plane divides by dot(mirrorDir, n), which
// explodes at grazing angles and produces non-zero MV in a static scene. Mirroring is division-free, so
// static MV is bit-exact zero at any incidence. The reflector is assumed static (terrain / water);
// reflectedMotionPrev carries the reflected content's own displacement.
public float2 previousReflectionNdc(float3 surfacePos, float3 surfaceNormal, float3 surfaceMotionPrev,
                             float3 reflectedWorldPos, float3 reflectedMotionPrev, out bool valid) {
    float3 n = normalize(surfaceNormal); // mirror(P) is orientation-independent, so no viewer flip needed
    float3 previousSurfacePos = surfacePos - surfaceMotionPrev;
    float3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    float3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - previousSurfacePos, n);
    return projectPrevNdc(mirroredHit, valid);
}

// Reflection guides remain owned by the physical foreground interface even when transmission later
// replaces the ordinary guide tuple. The same non-SER probe resolves both motion and, for exact
// mirrors, the reflected terminal's diffuse content. Destinations without a stable diffuse albedo keep
// the foreground material reflectance: sky, emissive surfaces, metals, and another dielectric/mirror
// would otherwise give RR a zero or recursively defined demodulation signal.
public float2 resolveSpecularGuides(SpecSurface surface, float3 primaryDir,
                             float2 currentNdc, float2 size, float primaryConeSpread,
                             out float3 specAlbedo) {
    specAlbedo = surface.albedo;
    if (length(surface.normal) < 0.5
            || max(surface.albedo.r, max(surface.albedo.g, surface.albedo.b)) <= 0.001
            || surface.roughness > SPEC_MOTION_ALPHA_MAX) {
        return float2(0.0, 0.0);
    }

    float3 surfacePos = surface.camRel + worldPush.camOffset;
    float3 n = normalize(surface.normal);
    float3 specDir = reflect(primaryDir, n);

    // Pass A stops radiance traversal at the first split, so reflection motion keeps one dedicated,
    // deterministic guide probe.
    traceGuide(CULL_SECONDARY,
            offsetSurfaceOrigin(surfacePos, surface.biasNormal, specDir, SURF_BIAS),
            RAY_TMIN, specDir, 10000.0,
            max(length(surface.camRel) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    float3 reflectedHit;
    float3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = surfacePos + specDir * payload.hitT;
        uint reflectedMaterial = payloadMaterial();
        // Closest-hit writes motionPrev for every material. Terrain/water are zero; dynamic opaque and
        // dielectric entities carry their per-vertex/object displacement.
        reflectedMotionPrev = payload.motionPrev;
        if (surface.roughness <= MIRROR_ALPHA_MAX
                && (reflectedMaterial == MATERIAL_OPAQUE
                    || reflectedMaterial == MATERIAL_PARTICLE)
                && payloadEmission() <= 0.0) {
            float3 reflectedDiffuse = reflectedMaterial == MATERIAL_PARTICLE
                    ? float3(payload.albedo)
                    : float3(payload.albedo) * (1.0 - clamp(payloadMetalness(), 0.0, 1.0));
            if (max(reflectedDiffuse.r, max(reflectedDiffuse.g, reflectedDiffuse.b)) > 0.001) {
                specAlbedo = surface.albedo * reflectedDiffuse;
            }
        }
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6;
        reflectedMotionPrev = float3(0.0, 0.0, 0.0);
    }
    float3 previousN = length(surface.previousNormal) >= 0.5
            ? normalize(surface.previousNormal) : n;
    bool prevValid;
    float2 prevNdc = previousReflectionNdc(
            surfacePos, previousN, surface.motionPrev,
            reflectedHit, reflectedMotionPrev, prevValid);
    return prevValid ? (prevNdc - currentNdc) * 0.5 * size : float2(0.0, 0.0);
}

public void setTransmissionGuide(float3 hitCamRel, float3 motionPrev, float3 normal,
                          float roughness, float3 diffuseAlbedo) {
    gv_hitCamRel = hitCamRel;
    gv_motionObjDisp = motionPrev;
    gv_motionUseRefracted = true;
    gv_normal = normal;
    gv_rough = roughness;
    gv_albedo = diffuseAlbedo;
}

// Per-channel floor for the transmittance folded into the transmitted diffuse-albedo guide. FP16
// guide textures flush anything below ~6e-8 to zero, and a zero albedo makes every demodulating
// consumer (DLSS-RR internally, NRD/SVGF after their own floors) divide by ~0. 1e-3 clears both
// hazards while staying invisible: below it the transmitted lobe carries under 0.1% of the floor's
// diffuse, so the pixel is effectively the foreground Fresnel reflection and the guide is moot.
public static const float GUIDE_TRANSMITTANCE_MIN = 1.0e-3;

// Deterministic ordinary guide behind the first transmitted interface. This is guide-only work:
// radiance reflection/transmission continuations are queued at the first split and traced by Pass B.
//
// `guideFilter` arrives as the transmittance of the camera prefix (Pass A applied the same factor to
// the split throughputs) and leaves as the transmittance of the ENTIRE refracted walk: every segment
// multiplies in the Beer–Lambert attenuation of the medium it travelled through, exactly the factor
// Pass B applies to the real transmitted branch over the same deterministic hops. Folding it into the
// endpoint albedo is what keeps the denoiser's contract honest for translucent pixels:
//
//     colour_diffuse ≈ gAlbedo · destinationIrradiance
//
// The destination's irradiance is CONTINUOUS with the dry land beside the water — the absorption that
// makes the pixel darker lives in the colour, so it must live in the albedo too, or the demodulated
// signal the denoiser filters carries a fake luminance cliff at the waterline. DLSS-RR rebuilds that
// cliff as a bright ring around the body of water (most visible in caves, where the transmitted floor
// is the dominant lobe and RR's auto-exposure amplifies the dark frame), which is why the old guide —
// the destination albedo with NO water attenuation — read as a halo around water. NRD and SVGF
// demodulate by the same texture (and re-modulate by it downstream), so their round trip stays exact;
// only the domain they filter in improves.
public void resolveTransmissionGuide(float3 origin, float3 transmittedDir,
                              MediumStack medium,
                              float rayConeWidth, float rayConeSpread, float3 guideFilter) {
    if (dot(transmittedDir, transmittedDir) <= 0.0) return;

    float3 direction = normalize(transmittedDir);
    // `origin` arrives already offset off the foreground interface (Pass A uses
    // waterWaveContinueOrigin so a crest/trough cannot immediately re-hit the rest-plane mesh).
    float3 ro = origin;

    // The camera interface consumed bounce 0; the remaining configured bounce budget is the natural
    // cap for deterministic guide crossings too.
    for (uint crossing = 0u; crossing < worldPush.maxBounces; ++crossing) {
        traceGuide(CULL_PRIMARY, ro, RAY_TMIN, direction, 10000.0,
                rayConeWidth, rayConeSpread);
        if (payload.hitT <= 0.0) {
            setTransmissionGuide((ro + direction * 1.0e6) - worldPush.camOffset,
                    float3(0.0, 0.0, 0.0), float3(0.0, 0.0, 0.0), 1.0,
                    guideFilter * SKY_DIFF_ALBEDO);
            return;
        }

        uint material = payloadMaterial();
        float3 interfacePos = ro + direction * payload.hitT;
        // Transmittance of the segment just traced: Beer–Lambert over the medium it travelled in
        // (water extinction for water, one-block-reference volume extinction for glass), bounded
        // so a long segment cannot denormalize exp().
        // This replaces the old one-shot `guideFilter *= payload.albedo` per glass entry: the medium
        // the stack pushed already encodes that tint as extinction, so applying the physical factor
        // over the real segment length matches Pass B exactly instead of over-darkening thin panes.
        guideFilter = max(guideFilter * exp(-min(medium.current.extinction * payload.hitT,
                float3(32.0, 32.0, 32.0))), float3(GUIDE_TRANSMITTANCE_MIN));

        if (material == MATERIAL_OPAQUE || material == MATERIAL_PARTICLE) {
            float endpointMetalness = material == MATERIAL_PARTICLE
                    ? 0.0 : clamp(payloadMetalness(), 0.0, 1.0);
            float endpointRoughness = material == MATERIAL_PARTICLE
                    ? 1.0 : metallicShininessRoughness(clamp(payloadRoughness(), 0.0, 1.0), endpointMetalness);
            float3 endpointAlbedo = material == MATERIAL_PARTICLE
                    ? payload.albedo
                    : payload.albedo * (1.0 - endpointMetalness);
            setTransmissionGuide(interfacePos - worldPush.camOffset, payload.motionPrev,
                    payload.normal, endpointRoughness, guideFilter * endpointAlbedo);
            return;
        }
        if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) return;

        bool isWater = material == MATERIAL_WATER;
        bool entering = payloadDielectricEntering();
        float3 geometricNormal = normalize(payload.normal);
        float3 interfaceNormal = geometricNormal;
        WaterWaves waterSurf = flatWaterWaves(interfacePos, payload.hitT, geometricNormal);
        if (isWater && waterWavesEnabled()) {
            float waterFootprint = rayConeWidth
                    / max(abs(dot(-direction, geometricNormal)), 0.2);
            waterSurf = evaluateWaterWaves(ro, direction, payload.hitT, geometricNormal,
                    worldPush.waterParams.w, worldPush.waterParams.w, waterFootprint);
            interfaceNormal = waterSurf.normal;
            interfacePos = waterSurf.position;
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0),
                                              payload.albedo, transmission);
        float etaT = entering ? entered.ior : medium.outer.ior;
        float3 nextDirection = refract(direction, interfaceNormal, medium.current.ior / etaT);
        if (dot(nextDirection, nextDirection) <= 0.0) {
            // Never let a TIR reflection become ordinary diffuse/depth.
            setTransmissionGuide(interfacePos - worldPush.camOffset,
                    isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                    interfaceNormal, 0.0, float3(0.0, 0.0, 0.0));
            return;
        }
        // (No per-interface tint multiply for glass here: the medium push/pop below plus the
        // per-segment factor above applies the tint's extinction over the true segment length.)
        if (entering) {
            mediumPush(medium, entered);
        } else {
            mediumPop(medium);
        }
        direction = normalize(nextDirection);
        ro = isWater && waterWavesEnabled()
                ? waterWaveContinueOrigin(waterSurf, direction,
                        isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS)
                : offsetSurfaceOrigin(interfacePos, geometricNormal, direction,
                        isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS);
    }
}

// Resolve the captured gv_* guide state into the DLSS-RR guide images after Pass A queues continuations.
public void writeGuides(int2 pix, float3 primaryDir, float2 jndc, float2 size, float primaryConeSpread) {
    // Reflection remains owned by the foreground interface even when a dielectric replaced the ordinary
    // tuple with the transmitted destination.
    float3 specAlbedo;
    float2 specMotion = resolveSpecularGuides(
            gv_spec, primaryDir, jndc, size, primaryConeSpread, specAlbedo);

    // Hardware (non-linear, reversed-Z) depth for DLSS-RR + Frame Generation: project the camera-relative
    // primary hit through the forward view-projection and take ndc z/w — exactly the value a rasterizer
    // would write. Reversed-Z (near=1, far=0), so DLSS gets the DepthInverted flag; sky's far hit -> ~0.
    // Clear transmission uses destination depth so the entire ordinary tuple describes one layer.
    float4 curClip = mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0));
    float depth = curClip.w > 0.0 ? curClip.z / curClip.w : 0.0;

    // NRD IN_VIEWZ / SVGF reprojection guide: linear view depth of the primary hit (positive, grows forward;
    // the perspective divide's w is exactly that distance). Sky/miss pixels place gv_hitCamRel at
    // 1e6 blocks, so their viewZ lands above CommonSettings::denoisingRange — how REBLUR recognizes
    // INF pixels and how SVGF recognizes sky. Written here (Pass A) because the
    // primary hit is owned here; Pass B only reads it for hit-distance normalization.
    if (nrdEnabled() || viewZEnabled()) {
        // BUGFIX: for sky, curClip.w works out to ~1e6 * |rd.z_view|, which is DIRECTION-dependent:
        // sky rays near the view axis exceed denoisingRange (correctly INF) but sky rays far from the
        // axis fall BELOW it, so the NRD mistakes edge-of-screen sky for geometry and tries to denoise
        // it — a visible circular boundary between "sky treated as sky" and "sky treated as geometry".
        // Detect sky robustly by hit distance instead: sky sits at |gv_hitCamRel| = 1e6 exactly (the
        // miss path writes rd*1e6, sq = 1e12), while real geometry never approaches that — even far LOD
        // terrain stays well under 1e5 blocks (sq < 1e10). A 1e11 threshold separates the two with wide
        // margins on both sides. Force sky viewZ to a guaranteed-INF value regardless of ray direction.
        float hitDistSq = dot(gv_hitCamRel, gv_hitCamRel);
        bool isSky = hitDistSq > 1.0e11;
        gViewZ[pix] = isSky ? 1.0e6 : (curClip.w > 0.0 ? min(curClip.w, 1.0e6) : 1.0e6);
    }

    // Motion vector: reproject this guide point through the previous frame's view-projection.
    // The primary ray was cast through the JITTERED ndc (jndc), so this hit's current screen position is
    // jndc, NOT the pixel centre. Subtract jndc so the MV is jitter-free (= 0 when static), which is
    // what DLSS expects with MVJittered unset. Scaled from NDC into render-pixel space.
    // For a dynamic surface, previous-frame position is the current point minus its world-space
    // displacement; subtracting gv_motionObjDisp de-cameras the MV.
    float4 prevClip = mul(worldPush.prevViewProj,
            float4(gv_hitCamRel + worldPush.camDelta - gv_motionObjDisp, 1.0));
    float2 curNdc = jndc; // primary hit's current screen position is exactly the jittered ray ndc
    // Transmitted content is its own feature: compare its previous and current projections rather than
    // subtracting the primary interface's jittered NDC.
    float4 curClipMotion = gv_motionUseRefracted
            ? mul(worldPush.curViewProj, float4(gv_hitCamRel, 1.0))
            : float4(0.0, 0.0, 0.0, 1.0); // unused; keeps the guard below uniform
    // Guard both perspective divides the way the depth above does. A guide point can land behind either
    // camera — most easily a transmitted destination seen at a grazing angle, or one that leaves the
    // frustum as the camera turns — and w at or below zero turns the divide into an arbitrarily large
    // vector that DLSS-RR reprojects with. No valid previous position exists in that case, so report no
    // motion and let RR fall back instead of handing it garbage.
    float2 motion;
    if (prevClip.w > 0.0 && (!gv_motionUseRefracted || curClipMotion.w > 0.0)) {
        if (gv_motionUseRefracted) curNdc = curClipMotion.xy / curClipMotion.w;
        motion = (prevClip.xy / prevClip.w - curNdc) * 0.5 * size;
    } else {
        motion = float2(0.0, 0.0);
    }

    // Guide buffers: first-hit or coherently replaced attributes consumed by the denoiser/DLSS-RR.
    gNormal[pix] = float4(gv_normal, gv_rough);
    gAlbedo[pix] = float4(gv_albedo, 1.0);
    gDepth[pix] = depth;
    gMotion[pix] = motion;
    gSpecAlbedo[pix] = float4(specAlbedo, 1.0);
    gSpecMotion[pix] = specMotion;
}

// 48-byte queue record. float3 + uint intentionally share the first 16-byte lane; every remaining

public void writeDebugView(int2 pix) {
    float4 normalRough = gNormal[pix];
    float3 dbg;
    if (pc.debugView == 1u) {
        dbg = normalRough.xyz * 0.5 + 0.5;
    } else if (pc.debugView == 2u) {
        dbg = gAlbedo[pix].rgb;
    } else if (pc.debugView == 3u) {
        dbg = float3(gDepth[pix], gDepth[pix], gDepth[pix]);
    } else if (pc.debugView == 4u) {
        dbg = float3(normalRough.w, normalRough.w, normalRough.w);
    } else if (pc.debugView == 6u) {
        dbg = gSpecAlbedo[pix].rgb;
    } else if (pc.debugView == 7u) {
        dbg = float3(clamp(0.5 + gSpecMotion[pix] * 0.05, 0.0, 1.0), 0.5);
    } else {
        dbg = float3(clamp(0.5 + gMotion[pix] * 0.05, 0.0, 1.0), 0.5);
    }
    outImage[pix] = float4(dbg, 1.0);
}
