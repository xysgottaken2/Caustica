// Water surface: the wave spectrum (height + analytic gradient and time derivative), the geometric
// displacement it drives, and the caustic term that differentiates the same field. Depends on core.
//
// Animated surface waves — a directional wave spectrum whose HEIGHT is intersected along the viewing
// ray (Newton on the rest plane), not a scrolling square texture. Three
// things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=18 m → ~5.3 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see evaluateWaterWaves) so the pattern is pinned in world space, and
// the caustics (waterCaustic) differentiate this same field, so they stay in sync with the visible
// ripples. Geometric displacement is what the Animated Water toggle actually does: the shading point,
// depth, and continuation origins ride the height field.

import world_common;
import world_core;

public static const float WATER_WAVE_STRENGTH = 0.9;  // slope + height scale (higher = taller, choppier waves)
public static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
public static const int   WAVE_COUNT = 7;             // λ = 28 m … ~1.6 m (golden-ratio steps; no 1-block tile chop)
public static const float WAVE_LAMBDA0 = 28.0;
public static const float WAVE_LAMBDA_RATIO = 1.618034; // incommensurate so crests cannot lock into a grid
public static const float WAVE_G = 9.81;
public static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
public static const float WAVE_MEANDER = 1.55;        // crest phase-wobble amplitude (rad) on the long components
public static const float WAVE_MAX_HEIGHT = 0.55;     // Newton search bound (blocks); > any in-phase crest
public static const int   WAVE_NEWTON_STEPS = 3;      // height-field ray iterations from the rest-plane hit
// Per-wave steepness a·k: energy stays in the swell. The old 10-octave 0.5 m chop read as the
// repeating vanilla still-water tile sitting on top of the waves.
public static const float WAVE_Q[WAVE_COUNT] = { 0.050, 0.058, 0.055, 0.048, 0.036, 0.024, 0.016 };

// Live Animated Water tuning, pushed per frame in the waterOpacity lanes (see WorldPush.waterOpacity
// in world_common.slang): y = wave height scale, z = animation speed scale, w = spectrum wave count.
// The sliders in the Water settings sub-screen write these; the clamps below keep a bad push from
// ever unbounding the spectrum walk or exploding the height field, and the authored constants above
// stay the 1.0 reference. Default push (1.0, 1.0, 7) reproduces the stock look bit-for-bit.
public float waterWaveStrengthScale() { return clamp(worldPush.waterOpacity.y, 0.0, 4.0); }
public float waterWaveSpeedScale() { return clamp(worldPush.waterOpacity.z, 0.0, 4.0); }
public int waterWaveCount() { return clamp(int(worldPush.waterOpacity.w + 0.5), 1, WAVE_COUNT); }

public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.25, 0.5, cyclesPerPixel);
}

// One walk of the wave spectrum, shared by every consumer so the spectrum constants exist once. WITH_DT
// is a generic so the derivative terms are compiled out entirely for callers that only want height and
// gradient — the trigonometry is shared, but the chain-rule work is not free.
public void waterWaveSpectrum<let WITH_DT : int>(float2 p, float t, float footprint,
                                          out float height, out float2 grad,
                                          out float heightDt, out float2 gradDt) {
    // Per-wave steepness a·k: energy stays in the swell. The old 10-octave 0.5 m chop read as the
    // repeating vanilla still-water tile (azulejo) sitting on top of the waves.
    const float Q[WAVE_COUNT] = { 0.050, 0.058, 0.055, 0.048, 0.036, 0.024, 0.016 };
    t *= WAVE_SPEED * waterWaveSpeedScale();
    int waveCount = waterWaveCount();
    height = 0.0;
    heightDt = 0.0;
    grad = float2(0.0, 0.0);
    gradDt = float2(0.0, 0.0);
    float lambda = WAVE_LAMBDA0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        if (i >= waveCount) break; // player detail slider: drop short-wave components first
        float lodWeight = waterWaveLodWeight(lambda, footprint);
        if (lodWeight <= 0.0) break;
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // Heading: wide irregular spread about the wind, with incommensurate offsets so wavefronts
        // cannot lock into a rectangular lattice.
        float spread = (0.28 + 0.22 * float(i)) * sin(float(i) * 2.399 + 1.3)
                     + 0.31 * sin(float(i) * 3.883 + 0.6)
                     + 0.17 * sin(float(i) * 5.236 + 2.1);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 2.399 * float(i) * float(i);
        float2 dph = k * d;
        float phDt = -w;                      // ∂φ/∂t
        float2 dphDt = float2(0.0, 0.0);      // ∂(∇φ)/∂t
        if (i < 6) { // meander every swell component whose front would otherwise rule a straight line
            float2 pd = float2(-d.y, d.x);
            float wph = 0.37 * k * dot(pd, p) + 0.47 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.37 * k) * pd;
            if (WITH_DT != 0) {
                float wphDt = 0.47 * w;
                phDt += WAVE_MEANDER * cos(wph) * wphDt;
                dphDt = (-WAVE_MEANDER * sin(wph) * wphDt * 0.37 * k) * pd;
            }
        }
        float S = 1.15 + 0.08 * float(i); // mild crest sharpening; keep the profile as waves, not tiles
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float e = exp(S * (sinPh - 1.0));
        // a = Q/k; h = a·e^{S(sin φ−1)}; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        float a = lodWeight * (WAVE_Q[i] / k);
        height += a * e;
        grad += a * S * cosPh * e * dph;
        if (WITH_DT != 0) {
            heightDt += a * e * S * cosPh * phDt;
            gradDt += a * S * e
                    * (phDt * (-sinPh + S * cosPh * cosPh) * dph + cosPh * dphDt);
        }
        lambda /= WAVE_LAMBDA_RATIO;
    }
    // The height/slope scale folds the player's Wave Height slider into the authored strength; the
    // time derivatives scale with it (they derive from the same scaled amplitude).
    float strength = WATER_WAVE_STRENGTH * waterWaveStrengthScale();
    height *= strength;
    heightDt *= strength;
    grad *= strength;
    gradDt *= strength;
}

public float2 waterWaveGrad(float2 p, float t, float footprint) {
    float height, heightDt;
    float2 grad, gradDt;
    waterWaveSpectrum<0>(p, t, footprint, height, grad, heightDt, gradDt);
    return grad;
}

public float2 waterWaveGrad(float2 p, float t) {
    return waterWaveGrad(p, t, 0.0);
}

public float waterWaveHeight(float2 p, float t, float footprint) {
    float height, heightDt;
    float2 grad, gradDt;
    waterWaveSpectrum<0>(p, t, footprint, height, grad, heightDt, gradDt);
    return height;
}

// Evaluate the current gradient/height and linearly rewind them to the previous phase in one spectrum
// walk. Frame deltas are bounded on the CPU, so this avoids a second set of trigonometric/exponential
// work while remaining accurate over the sub-frame interval used by reflection reprojection.
public void waterWaveFieldTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float height, out float2 grad,
                           out float previousHeight, out float2 previousGrad) {
    float heightDt;
    float2 gradDt;
    waterWaveSpectrum<1>(p, currentT, footprint, height, grad, heightDt, gradDt);
    // Scaled-time rewind: the spectrum above evaluates at t' = t * WAVE_SPEED * speedScale, and the
    // derivatives are w.r.t. that same scaled time, so the frame delta converts with both factors.
    float dt = (currentT - previousT) * WAVE_SPEED * waterWaveSpeedScale();
    previousGrad = grad - dt * gradDt;
    previousHeight = height - dt * heightDt;
}

public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float2 currentGrad, out float2 previousGrad) {
    float height, previousHeight;
    waterWaveFieldTemporal(p, currentT, previousT, footprint,
            height, currentGrad, previousHeight, previousGrad);
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t, footprint);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

// Geometric wave hit: the rest-plane triangle the BLAS actually contains, plus the height-field point
// the shading/depth/continuations should use. Side faces leave position == planePos.
public struct WaterWaves {
    public float3 position;         // displaced shading point (world, rebased)
    public float3 planePos;         // original mesh hit
    public float hitT;              // ray t of `position`
    public float3 normal;           // waved, viewer-oriented
    public float3 previousNormal;   // same normal one frame ago
    public float3 geometricNormal;  // unrippled mesh normal (ray-origin bias)
    public float3 motionPrev;       // current-minus-previous displacement of the surface
};

public WaterWaves flatWaterWaves(float3 planePos, float hitT, float3 nGeo) {
    WaterWaves waves;
    waves.position = planePos;
    waves.planePos = planePos;
    waves.hitT = hitT;
    waves.normal = nGeo;
    waves.previousNormal = nGeo;
    waves.geometricNormal = nGeo;
    waves.motionPrev = float3(0.0, 0.0, 0.0);
    return waves;
}

// Intersect the viewing ray with the displaced height field, starting from the rest-plane hit the
// acceleration structure reported. Crests are hit earlier, troughs later; the analytic gradient of
// the same field becomes the shading normal, so glints, refraction and the visible relief stay in
// lockstep. Side faces (|n.y| small) and near-horizontal rays keep the mesh hit.
public WaterWaves evaluateWaterWaves(float3 ro, float3 rd, float hitT, float3 nGeo,
                                     float currentT, float previousT, float footprint) {
    float3 planePos = ro + rd * hitT;
    if (abs(nGeo.y) < 0.5) {
        return flatWaterWaves(planePos, hitT, nGeo);
    }

    float2 domain0 = planePos.xz + worldPush.waterAnchor.xy;
    float invNy = 1.0 / nGeo.y;
    float absRdY = max(abs(rd.y), 0.05);
    float tMin = max(hitT - WAVE_MAX_HEIGHT / absRdY, 0.0);
    float tMax = hitT + WAVE_MAX_HEIGHT / absRdY;
    float t = hitT;
    float height = 0.0;
    float2 grad = float2(0.0, 0.0);

    [unroll]
    for (int i = 0; i < WAVE_NEWTON_STEPS; i++) {
        float3 pos = ro + rd * t;
        float2 domain = domain0 + rd.xz * (t - hitT);
        float heightDt;
        float2 gradDt;
        waterWaveSpectrum<0>(domain, currentT, footprint, height, grad, heightDt, gradDt);
        float restY = planePos.y
                - (nGeo.x * (pos.x - planePos.x) + nGeo.z * (pos.z - planePos.z)) * invNy;
        float f = pos.y - restY - height;
        float dRest = -(nGeo.x * rd.x + nGeo.z * rd.z) * invNy;
        float df = rd.y - dRest - dot(grad, rd.xz);
        float safeDf = abs(df) < 1.0e-3 ? (df < 0.0 ? -1.0e-3 : 1.0e-3) : df;
        t = clamp(t - f / safeDf, tMin, tMax);
    }

    float3 pos = ro + rd * t;
    float2 domain = domain0 + rd.xz * (t - hitT);
    float previousHeight;
    float2 previousGrad;
    waterWaveFieldTemporal(domain, currentT, previousT, footprint,
            height, grad, previousHeight, previousGrad);

    float orientation = nGeo.y >= 0.0 ? 1.0 : -1.0;
    WaterWaves waves;
    waves.position = pos;
    waves.planePos = planePos;
    waves.hitT = t;
    waves.normal = orientation * normalize(float3(-grad.x, 1.0, -grad.y));
    waves.previousNormal = orientation * normalize(float3(-previousGrad.x, 1.0, -previousGrad.y));
    waves.geometricNormal = nGeo;
    waves.motionPrev = float3(0.0, height - previousHeight, 0.0);
    return waves;
}

// Continuation origin that stays on the correct side of BOTH the displaced surface and the rest-plane
// triangle the BLAS still contains, so a trough cannot immediately re-hit its own mesh on the way out
// and a crest cannot immediately re-hit it on the way in.
public float3 waterWaveContinueOrigin(WaterWaves waves, float3 rd, float bias) {
    float3 sideNormal = dot(waves.geometricNormal, rd) >= 0.0
            ? waves.geometricNormal : -waves.geometricNormal;
    float3 origin = waves.position + sideNormal * bias;
    float goingOut = dot(rd, waves.geometricNormal);
    float planeSide = dot(waves.geometricNormal, origin - waves.planePos);
    if (goingOut > 0.0) {
        if (planeSide < bias) origin += waves.geometricNormal * (bias - planeSide);
    } else {
        if (planeSide > -bias) origin -= waves.geometricNormal * (planeSide + bias);
    }
    return origin;
}

// ---- Water caustics. Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true focusing factor computable
// instead of faked with a scrolling texture. Where the shadow ray of an underwater NEE vertex crossed
// water (returned by visibility() as VisibilityResult.waterHitT), evaluate the horizontal landing
// position of the refracted sun ray as a function of surface position and finite-difference it. The
// caustic intensity is the inverse Jacobian determinant of that surface→floor mapping (area compression = brightening, real
// fold caustics where det → 0). Because this uses the SAME wave field as the visible surface normals,
// the caustic pattern stays in sync with the ripples, and the per-sample sun-quad jitter (sampleSquare)
// shifts the pattern per sample → caustics physically blur with depth under DLSS-RR accumulation.
public static const float CAUSTIC_EPS = 0.25; // finite-difference step (m); also low-passes sub-step wave detail
public static const float CAUSTIC_MAX = 6.0;  // focusing clamp: keeps fold-line singularities from becoming fireflies

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz, intersected
// with the receiver plane h metres below. inc = sunlight travel direction (downward, unit).
public float2 causticLanding(float2 xz, float t, float3 inc, float h) {
    float2 g = waterWaveGrad(xz, t);
    float3 nw = normalize(float3(-g.x, 1.0, -g.y));
    float3 rdw = refract(inc, nw, 1.0 / WATER_IOR); // air→water: never TIR
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

public float waterCaustic(float3 exitPos, float3 lightDir, float waterDist) {
    // Grazing light: the receiver-plane intersection degenerates and caustics vanish in reality anyway
    // (long slant paths absorb + the pattern smears out) — fade the effect to neutral 1.0.
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return 1.0;
    float h = max(waterDist * lightDir.y, 0.05); // vertical depth of the receiver below the exit point
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + worldPush.waterAnchor.xy;  // world-stable wave domain (same as evaluateWaterWaves)
    float2 p0 = causticLanding(base, t, inc, h);
    float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0), t, inc, h);
    float2 pz = causticLanding(base + float2(0.0, CAUSTIC_EPS), t, inc, h);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    // Flat water ⇒ all landings displace identically ⇒ det = eps² ⇒ focus = 1 (energy-neutral); the
    // clamp bounds the fold-line spikes whose energy the surrounding darkening already paid for.
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    return lerp(1.0, min(focus, CAUSTIC_MAX), fade);
}
