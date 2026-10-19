// Two existing cloud styles, one depth-correct participating-medium interface.
// Classic: vanilla clouds.png cells, analytic boxes and EnvironmentAttributes.CLOUD_COLOR.
// Volumetric: the existing procedural coverage field organises a true 3D Perlin-Worley medium.
// No image/volume texture or extra cloud pass is used for volumetric shape or lighting.
//
// The classic percentage depth is preserved. Volumetric has independent 16..22-block envelope
// endpoints in WorldPush.cloudThickness; opacity is still a ceiling on the finished composite.
// Weather fill and the wind anchor are shared; both visible geometry and shadows use that state.
//
// Every visible query stops at the nearest scene hit, including secondary/reflected/refracted rays.
// Only world_common is imported: world.rmiss must NOT pull in world_core's raygen bindings.

import world_common;

// ---- Pattern geometry. The original value-noise lattice repeats at 12 * 512 = 6144 blocks.
// The COMPLETE volume also samples 24/48-block wavelengths, so the host already wraps its wind +
// camera anchor at 24576 blocks. Keep every octave periodic over that distance (see below), including
// the thickness/type fields. A periodic hash alone does not make arbitrary frequency scales periodic.
public static const float CLOUD_CELL_BLOCKS = 12.0; // vanilla's cloud cell size
public static const int   CLOUD_PERIOD_CELLS = 512; // power of two, so the wrap below is a mask
public static const int   CLOUD_CELL_MASK = CLOUD_PERIOD_CELLS - 1;
// Width of the coverage ramp for the volumetric style. Classic quantises instead (see cloudCoverage).
public static const float CLOUD_EDGE_SOFTNESS = 0.22;
public static const float3 CLOUD_ALBEDO = float3(0.94, 0.95, 0.98);
public static const float CLOUD_INV_PI = 0.31830988618;

// Below this the deck is treated as a flat sheet: the thickness slider at 0 must reproduce the old
// plane exactly rather than degenerate into a zero-length march.
public static const float CLOUD_FLAT_EPSILON = 0.25;

// Style ids; the active one comes from featureFlags (see cloudStyle).
public static const uint CLOUD_STYLE_CLASSIC = 0u;
public static const uint CLOUD_STYLE_VOLUMETRIC = 1u;

// ---- Budgets. These legacy constants are kept ONLY for the classic missing-map fallback.
// A volumetric preset bounds both the base sampling rate and the adaptive cap, so Low cannot silently
// grow back to Ultra cost at grazing angles. highQuality still distinguishes visible/specular rays
// from diffuse indirect rays; it is not the player's quality selector.
public static const int   CLOUD_MARCH_STEPS = 24;
public static const int   CLOUD_LIGHT_STEPS = 6;
public static const int   CLOUD_MARCH_STEPS_CHEAP = 6;
public static const int   CLOUD_LIGHT_STEPS_CHEAP = 2;
public static const int   CLOUD_MARCH_STEPS_MAX = 64;
public static const int   CLOUD_MARCH_STEPS_CHEAP_MAX = 12;
public static const float CLOUD_TARGET_STEP_BLOCKS = 1.5;

struct CloudQualitySettings {
    int viewSteps;           // integration intervals per vertical slab; short segments need fewer
    int viewCap;            // hard bound even at the horizon
    int diffuseSteps;
    int diffuseCap;
    int lightSteps;          // per occupied visible sample
    int diffuseLightSteps;
    int shadowSteps;         // per terrain/fog shadow query, after scene visibility
    int shadowCap;           // grazing shadows must resolve intervening banks, even on Low
    int skySteps;            // per occupied visible sample toward the sky
    int diffuseSkySteps;     // zero uses the previous local-column estimate
    int multipleOrders;     // extra phase/attenuation orders; zero disables them
    int densityProbes;      // Low/Medium: two-point quadrature, one lighting query per interval
};

int cloudQualityLevel(WorldPush push) {
    return int(clamp(push.cloudThickness.z, 0.0, 4.0));
}

CloudQualitySettings cloudQualitySettings(WorldPush push) {
    // Keep Low's vertical budget at eight, but allow 24 along long/grazing paths: a 12-step cap
    // visibly sliced curved banks into bands in the CPU diagnostic. Light/detail work dominates;
    // Low/Medium use two density probes per visible interval, but shade only their density-weighted
    // centroid. This reduces coherent midpoint bands without temporal noise or doubling light marches.
    // Low costs at most 24*(2+2+1) density queries versus Ultra's 64*(1+6+3).
    //                  view/cap  diffuse/cap  sun/diffuse  shadow/cap  sky/diffuse  MS  density probes
    switch (cloudQualityLevel(push)) {
        case 0: return { 8, 24,    3,  4,       2, 1,        4, 12,      1, 0,       0, 2 }; // Low
        case 1: return {12, 32,    4,  6,       2, 1,        5, 16,      1, 0,       1, 2 }; // Medium
        case 2: return {16, 40,    5,  8,       4, 2,        6, 20,      2, 1,       1, 1 }; // High
        case 3: return {20, 48,    6, 10,       5, 2,        7, 24,      2, 1,       2, 1 }; // Very high
        default:return {24, 64,    6, 12,       6, 2,        8, 28,      3, 2,       2, 1 }; // Ultra
    }
}

public static const float CLOUD_EXTINCTION = 0.115; // per block, at density 1
// Optical depth is normalised against this thickness, so making the deck taller adds VOLUME without
// making it more opaque. Without it, tau = density * sigma * pathLength grows with the slab depth and
// the thickness slider silently doubles as a second opacity slider.
public static const float CLOUD_REFERENCE_THICKNESS = 40.0;
// Longest in-slab path a single ray may integrate, in slab depths. Bounds the grazing/horizon case
// (see cloudMarch); a few crossings already saturate a real cloud.
public static const float CLOUD_MAX_SLAB_CROSSINGS = 3.5;
// Retained macro coverage scale: 24-block structures organised by the existing 12-block lattice.
public static const float CLOUD_VOLUMETRIC_SCALE = 0.5;
public static const float CLOUD_HG_G = 0.72;
public static const float CLOUD_HG_BACK = -0.28;
// Classic clouds are opaque boxes; this scales their extinction so a box saturates within its own
// thickness rather than looking like translucent haze.
public static const float CLOUD_CLASSIC_EXTINCTION_SCALE = 6.0;

// ---- Authored cell map (classic style).
//
// The classic deck reads its SHAPE from vanilla's own textures/environment/clouds.png, uploaded by
// RtComposite into this frame's BDA ring slot as a bit-packed occupancy bitmap (one bit per pixel,
// 256x256, one pixel = one 12-block cell; a cell counts as cloud when its alpha is >= 10 — vanilla's
// isCellEmpty threshold). Cloud shape is therefore AUTHORED data, exactly what vanilla extrudes,
// instead of an emergent property of the noise field below — which is what stops "fix one classic
// bug, surface another" from happening: there is no coverage threshold to tune against the shape.
//
// The map is exactly periodic over 256 cells * 12 blocks = 3072 blocks. The anchor RtComposite pushes
// is wrapped to 24576 blocks, an exact multiple of 3072, so the wrap is seamless by construction —
// the same property the noise field needs its power-of-two constants for, for free.
public static const int   CLOUD_CELL_MAP_CELLS = 256;
public static const int   CLOUD_CELL_MAP_MASK = CLOUD_CELL_MAP_CELLS - 1;
// Longest cell walk a single classic ray may perform. A near-horizontal ray inside the slab could in
// principle cross the whole view limit (3072/12 = 256 cells); the cap sits above that so the walk can
// always finish, while a pathological configuration can never turn the deck into an unbounded loop.
public static const int   CLOUD_CLASSIC_MAX_CROSSINGS = 320;

/**
 * Whether this frame draws clouds at all. Coverage or opacity at zero answers false, so the player
 * sliders bottoming out costs one comparison rather than a wasted intersection, and every consumer
 * short-circuits to the exact pre-cloud result (bit-identical output when the feature is off).
 */
public bool cloudsEnabled(WorldPush push) {
    // clouds.x is the PLAYER coverage only (the weather fill rides cloudColor.w) — so the deck stays
    // on when either one is non-zero. Gating on the slider alone would make a coverage-0 setting
    // remove the storm's overcast along with the clear-sky clouds.
    return push.dimension == DIMENSION_OVERWORLD
        && worldFeature(push, FEATURE_CLOUDS)
        && (push.clouds.x > 0.0 || push.cloudColor.w > 0.0)
        && push.clouds.y > 0.0;
}

/**
 * Selected style. Carried in featureFlags rather than smuggled into a float lane: that word exists
 * precisely for player-facing effect toggles, and an integer bit cannot be perturbed by denormal
 * flushing or a -0.0 comparison the way a packed sign bit can.
 */
public uint cloudStyle(WorldPush push) {
    return worldFeature(push, FEATURE_CLOUDS_VOLUMETRIC)
            ? CLOUD_STYLE_VOLUMETRIC : CLOUD_STYLE_CLASSIC;
}

/** Cloud shadow strength slider, 0..1. */
public float cloudShadowStrength(WorldPush push) {
    return clamp(push.clouds.z, 0.0, 1.0);
}

// Integer cell hash. Integer mixing rather than the sky's float hash13: the sample coordinate here is
// a world position that can be thousands of blocks from the anchor, where a float hash starts losing
// mantissa bits and the pattern degenerates into stripes. The mask is what makes the field periodic.
float cloudHash(int2 cell) {
    uint2 c = uint2(uint(cell.x & CLOUD_CELL_MASK), uint(cell.y & CLOUD_CELL_MASK));
    uint h = c.x * 374761393u + c.y * 668265263u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    h = h ^ (h >> 16u);
    return float(h & 0x00ffffffu) * (1.0 / 16777216.0);
}

// Value noise on the cell lattice, smoothstep-interpolated. floor/frac agree for negative coordinates
// (floor(-0.3) = -1, frac(-0.3) = 0.7), so the field is continuous across the origin.
float cloudNoise(float2 p) {
    int2 i = int2(floor(p));
    float2 f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = cloudHash(i);
    float b = cloudHash(i + int2(1, 0));
    float c = cloudHash(i + int2(0, 1));
    float d = cloudHash(i + int2(1, 1));
    return lerp(lerp(a, b, u.x), lerp(c, d, u.x), u.y);
}

/**
 * Raw coverage field in [0,1], before either style interprets it. Three octaves at INTEGER scales with
 * whole-cell offsets, so every octave shares the base period and the sum stays exactly periodic — see
 * the note on CLOUD_CELL_BLOCKS.
 *
 * Retained for volumetric macro coverage and the classic missing-map fallback.
 */
public float cloudCoverageField(float2 samplePos) {
    float2 p = samplePos / CLOUD_CELL_BLOCKS;
    return cloudNoise(p) * 0.6
         + cloudNoise(p * 2.0 + float2(11.0, 11.0)) * 0.3
         + cloudNoise(p * 4.0 + float2(29.0, 29.0)) * 0.1;
}

/**
 * One authored cell from the clouds.png occupancy bitmap (see CLOUD_CELL_MAP_CELLS). `cell` is in
 * 12-block cell space; the map wraps at 256, matching the texture's own tiling.
 */
bool cloudCellOccupied(WorldPush push, int2 cell) {
    int2 c = int2(cell.x & CLOUD_CELL_MAP_MASK, cell.y & CLOUD_CELL_MAP_MASK);
    uint index = uint(c.y * CLOUD_CELL_MAP_CELLS + c.x);
    ConstPtr<uint> map = ConstPtr<uint>(push.cloudCellsAddr);
    return (map[index >> 5u] & (1u << (index & 31u))) != 0u;
}

// The analytic classic path used to read ONLY the bitmap, bypassing the weather fill that the
// coverage/shadow query already applied. Share the stable weather decision without changing any
// clear-weather authored cells (the classic UI deliberately has no coverage/thinning control).
bool cloudWeatherCellFilled(WorldPush push, int2 cell) {
    float fill = saturate(push.cloudColor.w);
    if (fill <= 0.0) return false;
    if (fill >= 1.0) return true;
    int2 wrapped = cell & int2(CLOUD_CELL_MAP_MASK, CLOUD_CELL_MAP_MASK);
    return cloudHash(wrapped + int2(127, 61)) < fill;
}

bool cloudClassicBoxOccupied(WorldPush push, int2 cell) {
    return cloudWeatherCellFilled(push, cell) || cloudCellOccupied(push, cell);
}

/**
 * Coverage resolved into a 0..1 "how much cloud is here" for the current style.
 *
 * The two lanes it reads: push.clouds.x is the PLAYER coverage (the slider: the fraction of sky the
 * deck keeps in clear weather), push.cloudColor.w is the WEATHER fill (RtComposite ramps it toward 1
 * as rain/thunder close in). They stay separate all the way to the shader because the classic and
 * volumetric styles fold them together differently.
 *
 * CLASSIC reads the authored map. A cell is cloud when it is authored AND survives the slider's
 * thinning, OR when the weather has filled it in:
 *   * the per-cell hashes are evaluated on the cell index reduced to the map's own 256 period, so a
 *     given authored cell gets the SAME decision in every 3072-block tile — thinning or filling it
 *     differently across tiles would seam the sky at the wrap;
 *   * both tests are monotone in their input and stable over time, so raising the slider or a rising
 *     rain level only ever ADDS cells, and a full storm covers the whole sky (the old noise-quantised
 *     path topped out short of that and left punched holes right under the threshold — the reported
 *     bug 3);
 *   * with no weather and the slider at 1 the deck is exactly the clouds.png pattern — vanilla's own
 *     mix of large masses, small puffs and empty gaps, which no amount of noise tuning reproduces.
 * The quantised cell-centre sampling of the old path is gone with it: occupancy is a bitmap lookup,
 * so there is no threshold-vs-edge interaction left to produce holes.
 *
 * VOLUMETRIC keeps its smooth noise ramp, fed the weather-merged coverage. Full overcast short-
 * circuits to exactly 1: smoothstep never reaches 1 below threshold + EDGE, so a plain merge would
 * keep a residue of pinholes at "100%" coverage — and a storm sky has no pinholes.
 *
 * When the authored map is unavailable (cloudCellsAddr == 0 — texture missing, or a pack replaced it
 * with something non-256x256) the classic style degrades to the OLD noise-quantised deck rather than
 * to nothing, driven by the merged coverage so weather still closes the sky on that path.
 */
public float cloudCoverage(WorldPush push, float2 samplePos) {
    float retain = clamp(push.clouds.x, 0.0, 1.0);
    float fill = clamp(push.cloudColor.w, 0.0, 1.0);
    if (cloudStyle(push) == CLOUD_STYLE_CLASSIC) {
        if (push.cloudCellsAddr == 0u) {
            float merged = retain + (1.0 - retain) * fill;
            float threshold = 1.0 - clamp(merged, 0.0, 1.0);
            float2 cellCentre = (floor(samplePos / CLOUD_CELL_BLOCKS) + 0.5) * CLOUD_CELL_BLOCKS;
            return cloudCoverageField(cellCentre) > threshold + CLOUD_EDGE_SOFTNESS * 0.5 ? 1.0 : 0.0;
        }
        if (fill >= 1.0) {
            return 1.0; // a full storm is a full sky — skip the lookup entirely
        }
        int2 cell = int2(floor(samplePos / CLOUD_CELL_BLOCKS));
        int2 wrapped = int2(cell.x & CLOUD_CELL_MAP_MASK, cell.y & CLOUD_CELL_MAP_MASK);
        bool authored = cloudCellOccupied(push, cell) && cloudHash(wrapped) < retain;
        bool filled = cloudWeatherCellFilled(push, cell);
        return (authored || filled) ? 1.0 : 0.0;
    }
    return cloudMacroCoverage(push, samplePos);
}

/** Where a ray meets the deck, in the form both the visible pass and the shadow query need. */
public struct CloudHit {
    public bool hit;
    public float2 samplePos; // world-anchored deck coordinate to sample the density at
    public float distance;   // distance along the ray to the deck
    public float fade;       // horizon/view-limit fade
    public bool fromBelow;   // the deck is above the ray origin (we are looking at its underside)
};

/**
 * Intersect a ray with the deck's mid-plane. `originRel` and the deck height are both expressed
 * relative to the camera, which is what lets one function serve the miss shader (origin = the ray's own
 * origin, itself possibly a reflecting surface) and raygen (origin = the shading point) without either
 * needing absolute world coordinates — the terrain rebase would invalidate those anyway.
 *
 * `maxDistance` is the distance to the nearest scene hit along this ray (or a large number when the ray
 * escapes). A plane crossing BEYOND that point is behind solid geometry and is rejected, which is what
 * stops the deck from being drawn over terrain that is actually in front of it.
 *
 * Degenerate cases handled here rather than by callers, because each is a visible artefact:
 *   * a near-horizontal ray (|dir.y| tiny) would divide toward infinity — rejected outright, and the
 *     view-limit fade already takes alpha to zero long before that, so no edge appears;
 *   * a ray pointing away from the deck (t <= 0) must not intersect the plane behind it, which would
 *     paint a mirrored deck under the world.
 */
public CloudHit cloudTrace(WorldPush push, float3 originRel, float3 dir, float maxDistance) {
    CloudHit h;
    h.hit = false;
    h.samplePos = float2(0.0, 0.0);
    h.distance = 0.0;
    h.fade = 0.0;
    h.fromBelow = true;

    float deckRel = push.clouds.w - originRel.y; // deck mid-plane height relative to this ray's origin
    // Crossing fade. The flat style shades a plane, so a camera sitting exactly on it would flip
    // between the lit top face and the dark underside across a single pixel. Fading the deck out as the
    // camera passes through its nominal thickness turns that flip into a smooth transition — flying up
    // through the clouds dissolves them instead of popping. (The volumetric style needs no equivalent:
    // it integrates real density, so passing through simply marches less of it.)
    float halfThickness = max(push.cloudAnchor.z, 0.5) * 0.5;
    float crossFade = smoothstep(halfThickness * 0.5, halfThickness * 1.5, abs(deckRel));
    if (crossFade <= 0.0 || abs(dir.y) < 1.0e-4) {
        return h;
    }
    float t = deckRel / dir.y;
    if (t <= 0.0 || t >= maxDistance) {
        return h; // behind the ray, or behind solid geometry
    }
    float maxDist = max(push.cloudAnchor.w, 1.0);
    float horizontal = length(dir.xz) * t;
    if (horizontal >= maxDist) {
        return h;
    }
    h.hit = true;
    h.samplePos = push.cloudAnchor.xy + originRel.xz + dir.xz * t;
    h.distance = t;
    // The deck is a plane, so it reaches the horizon as an infinitely thin, infinitely dense band of
    // aliasing. Fading coverage out over the last 45% of the view limit replaces that band with open
    // sky, which is also how vanilla's finite cloud grid ends.
    h.fade = crossFade * (1.0 - smoothstep(maxDist * 0.55, maxDist, horizontal));
    h.fromBelow = deckRel > 0.0;
    return h;
}

/** Opacity the deck presents to a ray: density, the player's opacity slider, and the fades. */
public float cloudAlpha(WorldPush push, CloudHit h, float density) {
    if (!h.hit) {
        return 0.0;
    }
    return clamp(density * clamp(push.clouds.y, 0.0, 1.0) * h.fade, 0.0, 1.0);
}

/**
 * How much of the celestial (sun or moon) light survives the deck on its way to `pointRel`.
 *
 * Classic keeps its analytic footprint query. Volumetric integrates the actual bounded density
 * toward the light, including local thickness, erosion and weather. It uses the SAME extinction as
 * the eye/self-shadow march, so the ground shadow no longer samples an unscaled, unrelated footprint.
 * No hardware visibility ray is added here; callers already check scene occlusion first.
 *
 * This deliberately does NOT call cloudTrace/cloudAlpha. Those helpers describe a VISIBLE cloud layer:
 * they apply camera-facing cross-fades and the finite view-distance fade stored in cloudAnchor.w. Using
 * that view query for sun visibility made the cloud-shadow multiplier depend on the observer (not just
 * on the receiver and the sun): when the camera crossed above a block shadow, the same terrain hit could
 * receive a different cloud fade and the already-blocked direct light looked like it had been shadowed a
 * second time. Sun visibility must be a light-path query only, so it intersects the deck analytically and
 * samples coverage without any view/camera fade.
 */
public float cloudSunShadow(WorldPush push, float3 pointRel, float3 lightDir) {
    if (!cloudsEnabled(push)) {
        return 1.0;
    }
    float strength = cloudShadowStrength(push);
    if (strength <= 0.0 || lightDir.y <= 1.0e-4) {
        return 1.0;
    }

    if (cloudStyle(push) == CLOUD_STYLE_VOLUMETRIC) {
        float tau = cloudShadowOpticalDepth(push, pointRel, lightDir);
        return 1.0 - strength * saturate(push.clouds.y) * (1.0 - exp(-tau));
    }

    float deckRel = push.clouds.w - pointRel.y;
    float t = deckRel / lightDir.y;
    if (t <= 0.0) {
        return 1.0;
    }

    float2 samplePos = push.cloudAnchor.xy + pointRel.xz + lightDir.xz * t;
    float alpha = clamp(cloudCoverage(push, samplePos) * clamp(push.clouds.y, 0.0, 1.0), 0.0, 1.0);
    return 1.0 - strength * alpha;
}

// ---- Classic (vanilla-style) shading -------------------------------------------------------------

/**
 * Classic face shading, shared by the flat path and the analytic boxes. Vanilla shades cloud faces by
 * ORIENTATION with flat, constant tones — no smooth lighting model: top bright, underside markedly
 * darker, vertical sides in between. `faceLight` is that tone, picked by the caller from the face the
 * ray actually meets (which is a real geometric normal for boxes, and the view direction for the flat
 * sheet).
 *
 * Lit from worldPush.lightRadiance — the SAME value the ground's NEE uses — so the clouds ride every
 * curve the rest of the frame already follows: they redden through sunset on the atmosphere's own
 * transmittance, dim under the weather attenuation, and go to a dark sky-lit grey at night when the
 * moon takes over. Hand-picking a cloud colour is what makes cloud layers look pasted on.
 */
float3 cloudRadianceClassic(WorldPush push, float faceLight, float3 albedo, float3 skyBehind) {
    float3 sunRadiance = push.lightRadiance.xyz;
    float3 lit = albedo * CLOUD_INV_PI * sunRadiance * faceLight;
    // The classic deck is opaque, so it admits only a little sky fill — enough to keep it from going
    // black at night, when sunRadiance is near zero.
    return lit + skyBehind * 0.45;
}

/** Classic face tone for a unit face normal (see cloudRadianceClassic). */
float cloudClassicFaceTone(float3 normal) {
    if (normal.y > 0.5) {
        return 1.0;   // top face
    }
    if (normal.y < -0.5) {
        return 0.52;  // underside — vanilla's bottom face is markedly darker
    }
    return 0.78;      // vertical sides sit between the two
}

/**
 * The classic deck as REAL vanilla geometry: one 12x12-block box per authored clouds.png cell,
 * intersected analytically. No march, no density field — a cell is an axis-aligned box spanning the
 * slab, so the ray's crossing of the deck is a walk over the 2D cell grid (Amanatides & Woo style)
 * inside the slab's t-interval, stopping at the first occupied cell. That is exact by construction,
 * and it is what makes the classic style cheaper than a short march instead of the most expensive one:
 * the typical ray visits one or two cells.
 *
 * Two entry cases. An OUTSIDE ray meets the first occupied cell at the cell-boundary crossing the walk
 * arrives at — except when it enters the slab inside that cell, in which case the visible face is the
 * slab's top/bottom plane, exactly the face vanilla extrudes toward the viewer. A ray whose ORIGIN sits
 * inside an occupied box (the camera flying through a cloud) instead sees the box's exit face — vanilla
 * draws those interior faces for exactly this case (its FLAG_INSIDE_FACE ring).
 *
 * Depth is bounded by maxDistance like every other cloud entry point, so terrain in front of a box
 * occludes it; the horizon fade matches the other paths so the deck ends where they end.
 */
CloudVolume cloudClassicBoxes(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                              float3 skyBehind) {
    CloudVolume result;
    result.scatter = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;

    float thickness = max(push.cloudAnchor.z, 1.0);
    float slabBottom = push.clouds.w - thickness * 0.5 - originRel.y;
    float slabTop = push.clouds.w + thickness * 0.5 - originRel.y;

    // Slab entry/exit along the ray — identical bounds to cloudMarch, so both paths agree about where
    // the deck starts and stops for a given ray.
    float t0, t1;
    if (abs(dir.y) < 1.0e-6) {
        if (slabBottom > 0.0 || slabTop < 0.0) {
            return result; // parallel and outside the slab
        }
        t0 = 0.0;
        t1 = push.cloudAnchor.w;
    } else {
        float ta = slabBottom / dir.y;
        float tb = slabTop / dir.y;
        t0 = min(ta, tb);
        t1 = max(ta, tb);
    }
    t0 = max(t0, 0.0);
    t1 = min(t1, min(maxDistance, push.cloudAnchor.w));
    if (t1 <= t0) {
        return result;
    }
    float horizon = length(dir.xz) * t0;
    float fade = 1.0 - smoothstep(push.cloudAnchor.w * 0.55, push.cloudAnchor.w, horizon);
    if (fade <= 0.0) {
        return result;
    }

    // Grid walk happens in anchored deck space (anchor + camera-relative), where the authored cells are
    // axis-aligned and exactly periodic; y stays camera-relative because the slab bounds already are.
    float2 gridOrigin = push.cloudAnchor.xy + originRel.xz;
    float2 p0 = gridOrigin + dir.xz * t0;
    int2 cell = int2(floor(p0 / CLOUD_CELL_BLOCKS));

    float tHit = -1.0;
    float3 normal = float3(0.0, 1.0, 0.0);

    // t0 == 0 means the origin itself is inside the slab, so `cell` (built from the position at t0)
    // is the box the camera stands in — the visible surface is then the face the ray EXITS through
    // (vanilla draws those interior faces for exactly this case). For t0 > 0 the ray ENTERS the slab
    // at t0, and an occupied entry cell must be hit on its slab face right there: treating that case
    // as "inside" (the previous code did) skipped the near face entirely — from below, the deck then
    // showed only far faces and mid-slab side walls glimpsed through the gaps, which read as a
    // cybernetic grid in the clouds' shape instead of solid boxes.
    if (t0 <= 0.0 && cloudClassicBoxOccupied(push, cell)) {
        // Origin is inside an occupied box: the visible surface is the box face the ray EXITS through.
        float tExitX = 1.0e30;
        if (abs(dir.x) > 1.0e-8) {
            float boundX = dir.x > 0.0 ? (float(cell.x) + 1.0) * CLOUD_CELL_BLOCKS
                                       : float(cell.x) * CLOUD_CELL_BLOCKS;
            tExitX = (boundX - gridOrigin.x) / dir.x;
        }
        float tExitZ = 1.0e30;
        if (abs(dir.z) > 1.0e-8) {
            float boundZ = dir.z > 0.0 ? (float(cell.y) + 1.0) * CLOUD_CELL_BLOCKS
                                       : float(cell.y) * CLOUD_CELL_BLOCKS;
            tExitZ = (boundZ - gridOrigin.y) / dir.z;
        }
        float tExitY = dir.y > 0.0 ? slabTop / max(dir.y, 1.0e-8) : slabBottom / min(dir.y, -1.0e-8);
        if (tExitY <= tExitX && tExitY <= tExitZ) {
            tHit = tExitY;
            // Seen from inside, the ceiling IS the underside and the floor the top face — shade by the
            // face's own identity, not the viewing side, so the interior reads like vanilla's boxes.
            normal = float3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0);
        } else if (tExitX <= tExitZ) {
            tHit = tExitX;
            normal = float3(dir.x > 0.0 ? -1.0 : 1.0, 0.0, 0.0);
        } else {
            tHit = tExitZ;
            normal = float3(0.0, 0.0, dir.z > 0.0 ? -1.0 : 1.0);
        }
        if (tHit >= t1) {
            return result; // leaves the box beyond the bounded interval
        }
    } else {
        // Outside: walk the grid from the slab entry point until an occupied cell is crossed.
        // (A ray whose origin is OUTSIDE the slab but whose entry cell is occupied falls in here too:
        // the first-cell check below gives it the slab-face hit at t0 that the interior branch must
        // not steal — see the note above.)
        int stepX = dir.x >= 0.0 ? 1 : -1;
        int stepZ = dir.z >= 0.0 ? 1 : -1;
        float tDeltaX = abs(dir.x) > 1.0e-8 ? CLOUD_CELL_BLOCKS / abs(dir.x) : 1.0e30;
        float tDeltaZ = abs(dir.z) > 1.0e-8 ? CLOUD_CELL_BLOCKS / abs(dir.z) : 1.0e30;
        float edgeX = dir.x >= 0.0 ? (float(cell.x) + 1.0) * CLOUD_CELL_BLOCKS
                                   : float(cell.x) * CLOUD_CELL_BLOCKS;
        float edgeZ = dir.z >= 0.0 ? (float(cell.y) + 1.0) * CLOUD_CELL_BLOCKS
                                   : float(cell.y) * CLOUD_CELL_BLOCKS;
        float tMaxX = abs(dir.x) > 1.0e-8 ? (edgeX - p0.x) / dir.x + t0 : 1.0e30;
        float tMaxZ = abs(dir.z) > 1.0e-8 ? (edgeZ - p0.y) / dir.z + t0 : 1.0e30;
        // The slab entry point itself may already sit in an occupied cell — then the face seen is the
        // slab's own top/bottom plane, not a cell wall.
        if (cloudClassicBoxOccupied(push, cell)) {
            tHit = t0;
            normal = float3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0);
        } else {
            for (int i = 0; i < CLOUD_CLASSIC_MAX_CROSSINGS; i++) {
                float tNext;
                int3 stepAxis;
                if (tMaxX < tMaxZ) {
                    tNext = tMaxX;
                    cell.x += stepX;
                    tMaxX += tDeltaX;
                    stepAxis = int3(stepX, 0, 0);
                } else {
                    tNext = tMaxZ;
                    cell.y += stepZ;
                    tMaxZ += tDeltaZ;
                    stepAxis = int3(0, 0, stepZ);
                }
                if (tNext >= t1) {
                    break; // walked out of the bounded interval: open sky
                }
                if (cloudClassicBoxOccupied(push, cell)) {
                    tHit = tNext;
                    normal = float3(-stepAxis.x, 0.0, -stepAxis.z); // face the ray entered through
                    break;
                }
            }
        }
        if (tHit < 0.0) {
            return result; // no occupied cell before the interval ends
        }
    }

    float alpha = clamp(push.clouds.y, 0.0, 1.0) * fade;
    if (alpha <= 0.0) {
        return result;
    }
    // Authored colour: vanilla's own CLOUD_COLOR, already greyed by the game's weather resolve — this
    // is what fixes the white-in-rain deck without a hand-tuned ramp.
    float3 radiance = cloudRadianceClassic(push, cloudClassicFaceTone(normal), push.cloudColor.xyz,
                                           skyBehind);
    result.scatter = radiance * alpha;
    result.transmittance = 1.0 - alpha;
    return result;
}

// ---- Volumetric shading --------------------------------------------------------------------------

/** Single Henyey-Greenstein lobe. */
float hgLobe(float cosT, float g) {
    float g2 = g * g;
    float d = 1.0 + g2 - 2.0 * g * cosT;
    return (1.0 - g2) / (4.0 * 3.14159265359 * max(d * sqrt(max(d, 1.0e-6)), 1.0e-4));
}

/**
 * Dual-lobe phase. A single forward lobe gives the sun-facing glow but leaves the rest of the cloud
 * flat; real droplet scattering also has a weak backward lobe, which is what lights the side of a cloud
 * facing away from the sun and stops it collapsing into a silhouette. Mixing the two is the standard
 * cheap stand-in for a full Mie phase and costs one extra lobe evaluation.
 */
float cloudPhase(float cosT) {
    return lerp(hgLobe(cosT, CLOUD_HG_BACK), hgLobe(cosT, CLOUD_HG_G), 0.7);
}

// ---- Procedural volume --------------------------------------------------------------------------
// All horizontal wavelengths are 48, 24, 12, 6 or 3 blocks. With the 512-cell hash they divide the
// host's 24576-block anchor period exactly. Offsets/warps are periodic FIELDS, never rotations or
// non-integral frequency multipliers of the world position. No frame index enters this module.
public static const float CLOUD_FIELD_PERIOD_BLOCKS = 24576.0;
public static const float CLOUD_MIN_FORMATION_THICKNESS = 16.0;
public static const float CLOUD_MAX_FORMATION_THICKNESS = 22.0;

float2 cloudPeriodicPosition(float2 p) {
    return p - floor(p / CLOUD_FIELD_PERIOD_BLOCKS) * CLOUD_FIELD_PERIOD_BLOCKS;
}

uint cloudHash3(int3 cell) {
    uint3 c = uint3(cell & int3(CLOUD_CELL_MASK, CLOUD_CELL_MASK, CLOUD_CELL_MASK));
    uint h = c.x * 374761393u + c.y * 668265263u + c.z * 2246822519u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    return h ^ (h >> 16u);
}

// Improved Perlin gradient noise: signed gradients, not interpolated scalar values. Quintic weights
// keep derivatives continuous at lattice faces. The twelve edge gradients have equal length.
float cloudGradient(uint h, float3 p) {
    uint g = h & 15u;
    float u = g < 8u ? p.x : p.y;
    float v = g < 4u ? p.y : ((g == 12u || g == 14u) ? p.x : p.z);
    return ((g & 1u) == 0u ? u : -u) + ((g & 2u) == 0u ? v : -v);
}

float cloudPerlin3(float3 p) {
    int3 cell = int3(floor(p));
    float3 f = frac(p);
    float3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n[8];
    [ForceUnroll]
    for (int z = 0; z <= 1; z++) {
        [ForceUnroll]
        for (int y = 0; y <= 1; y++) {
            [ForceUnroll]
            for (int x = 0; x <= 1; x++) {
                int3 o = int3(x, y, z);
                n[x + 2 * y + 4 * z] = cloudGradient(cloudHash3(cell + o), f - float3(o));
            }
        }
    }
    return lerp(lerp(lerp(n[0], n[1], u.x), lerp(n[2], n[3], u.x), u.y),
                lerp(lerp(n[4], n[5], u.x), lerp(n[6], n[7], u.x), u.y), u.z);
}

// Exact F1 in the 27-cell neighbourhood. Restricting jitter to [.28,.72] guarantees a point in our
// own cell within sqrt(3)*.72 < 1.28, the minimum possible distance to any cell outside the stencil.
// Thus a neighbour entering/leaving the stencil cannot cause a discontinuity. The 3D domain warp
// below breaks up the otherwise visible cellular regularity without compromising that bound.
float cloudWorley3(float3 p) {
    int3 cell = int3(floor(p));
    float3 f = frac(p);
    float nearest = 3.0;
    [ForceUnroll]
    for (int z = -1; z <= 1; z++) {
        [ForceUnroll]
        for (int y = -1; y <= 1; y++) {
            [ForceUnroll]
            for (int x = -1; x <= 1; x++) {
                int3 o = int3(x, y, z);
                uint h = cloudHash3(cell + o);
                float3 jitter = float3(h & 1023u, (h >> 10u) & 1023u, (h >> 20u) & 1023u)
                        * (0.44 / 1023.0) + 0.28;
                float3 d = float3(o) + jitter - f;
                nearest = min(nearest, dot(d, d));
            }
        }
    }
    return sqrt(nearest);
}

float2 cloudWeatherWarp(float2 p) {
    float2 q = p / 48.0;
    return p + (float2(cloudNoise(q), cloudNoise(q + float2(41.0, 17.0))) - 0.5) * 12.0;
}

// Retain the existing coverage field as the large-scale organisation, not as an extruded shape.
// The same footprint is used by cloudCoverage; weather now reaches the actual marched density too.
float cloudMacroCoverage(WorldPush push, float2 samplePos) {
    float coverage = saturate(push.clouds.x);
    coverage += (1.0 - coverage) * saturate(push.cloudColor.w);
    if (coverage >= 1.0) return 1.0;
    float2 p = cloudWeatherWarp(cloudPeriodicPosition(samplePos));
    float base = cloudCoverageField(p * CLOUD_VOLUMETRIC_SCALE);
    float threshold = 1.0 - coverage;
    return smoothstep(threshold, threshold + CLOUD_EDGE_SOFTNESS, base);
}

// Thickness is a smooth regional PARAMETER, independent of coverage, density threshold and erosion.
// Each column's envelope is [base, base + thickness]. Individual lobes/edges may occupy less of it,
// never more. No hashed cell id selects a hard height at a boundary. Equal endpoints are exact.
float cloudFormationThickness(WorldPush push, float2 samplePos) {
    float lo = clamp(push.cloudThickness.x, CLOUD_MIN_FORMATION_THICKNESS, CLOUD_MAX_FORMATION_THICKNESS);
    float hi = clamp(push.cloudThickness.y, lo, CLOUD_MAX_FORMATION_THICKNESS);
    if (lo == hi) return lo;
    float2 p = cloudPeriodicPosition(samplePos) / 48.0;
    float region = smoothstep(0.12, 0.88, cloudNoise(p + float2(73.0, 19.0)));
    return lerp(lo, hi, region);
}

/** 3D density. The classic fallback remains a square-profile extrusion of its own coverage. */
float cloudVolumeDensity(WorldPush push, float3 posRel) {
    float2 samplePos = cloudPeriodicPosition(push.cloudAnchor.xy + posRel.xz);
    float slabBottom = push.clouds.w - push.cloudAnchor.z * 0.5;
    bool classic = cloudStyle(push) == CLOUD_STYLE_CLASSIC;
    float thickness = classic ? max(push.cloudAnchor.z, 1.0) : cloudFormationThickness(push, samplePos);
    float height = posRel.y - slabBottom;
    float h = height / thickness;
    // CLIP BEFORE warping/profile/erosion. Neither a warp nor a noise tail can grow the envelope.
    // Do not clamp h here: sampling beyond the slab must mean empty space, not its boundary density.
    if (h <= 0.0 || h >= 1.0) return 0.0;
    if (classic) {
        float faces = smoothstep(0.0, 0.06, h) * smoothstep(0.0, 0.06, 1.0 - h);
        return cloudCoverage(push, samplePos) * faces;
    }

    float3 p = float3(samplePos.x, height, samplePos.y) / 12.0;
    float3 warp = float3(cloudPerlin3(p * 0.25 + float3(0.0, 7.0, 0.0)),
                        cloudPerlin3(p * 0.25 + float3(37.0, 11.0, 17.0)),
                        cloudPerlin3(p * 0.25 + float3(19.0, 3.0, 53.0)));
    // All components (including Y) deform the noise, NOT the bounded envelope. The base remains
    // relatively flat while the upper body develops lobes; all scales advect with the same anchor.
    // Bend the large-scale envelope as well as the small lobes. Warping ONLY the detail leaves
    // straight-sided towers bounded by the original 2D coverage mask, even with fully 3D noise.
    float bend = smoothstep(0.05, 0.85, h);
    float coverage = cloudMacroCoverage(push, samplePos + warp.xz * (12.0 * bend));
    if (coverage <= 0.0) return 0.0;
    float type = cloudNoise(samplePos / 48.0 + float2(13.0, 97.0));
    float3 q = p + warp * (0.35 + 0.65 * bend);
    float perlin = cloudPerlin3(q * 0.5 + float3(7.0, 19.0, 29.0));
    float cells = 1.0 - saturate(cloudWorley3(q));
    // Perlin connects masses; Worley supplies rounded lobes in THREE dimensions. The asymmetric
    // envelope narrows the crown rather than multiplying a 2D mask into a stack of horizontal plates.
    float shapeNoise = saturate(0.30 + 0.28 * perlin + 0.70 * cells);
    // The crown closes to ZERO at the envelope top. Leaving a finite width there clips dense
    // Worley lobes into flat mesas, even though the density technically stayed within its bounds.
    float crown = pow(h, lerp(1.65, 2.4, type));
    float envelope = coverage * (0.60 + 0.40 * smoothstep(0.0, 0.35, h)) * (1.0 - crown);
    float shape = saturate((shapeNoise - (1.0 - envelope)) / max(envelope, 0.25));
    if (shape <= 0.0) return 0.0;

    // Secondary 6-block cellular scallops plus 3-block gradient erosion are restricted to the
    // boundary. They remove material, never expand it;
    // the core retains low-frequency density variation instead of becoming homogeneous white cotton.
    // Low filters the two fine bands to their mean instead of removing the erosion envelope
    // (which would inflate every formation). The 3D warp, 12-block lobes, profile and thickness are
    // identical at ALL presets. Medium keeps cellular scallops, High+ also resolves 3-block fraying.
    int detailLevel = cloudQualityLevel(push);
    float detail = 0.5;
    if (detailLevel >= 1) {
        float fine = 0.5;
        if (detailLevel >= 2) fine += 0.5 * cloudPerlin3(q * 4.0 + float3(11.0, 47.0, 3.0));
        detail = saturate(cloudWorley3(q * 2.0 + float3(23.0, 5.0, 41.0))) * 0.8 + fine * 0.2;
    }
    float erosion = detail * lerp(0.35, 0.60, type) * (1.0 - shape) * (1.0 - shape);
    float baseProfile = smoothstep(0.0, lerp(0.045, 0.10, type), h);
    float topProfile = 1.0 - smoothstep(0.86, 1.0, h);
    float interior = lerp(0.72, 1.12, type) * (0.85 + 0.15 * perlin);
    return saturate(shape - erosion) * baseProfile * topProfile * interior;
}

// A single physical extinction coefficient for eye rays, self-shadow and ground shadow. Normalising
// by LOCAL formation thickness (not the maximum slider) keeps opacity and geometry independent.
float cloudOpticalDensity(WorldPush push, float3 p) {
    float density = cloudVolumeDensity(push, p);
    if (density <= 0.0) return 0.0;
    bool classic = cloudStyle(push) == CLOUD_STYLE_CLASSIC;
    float thickness = classic ? max(push.cloudAnchor.z, 1.0)
                             : cloudFormationThickness(push, push.cloudAnchor.xy + p.xz);
    return density * CLOUD_EXTINCTION * CLOUD_REFERENCE_THICKNESS / thickness
            * (classic ? CLOUD_CLASSIC_EXTINCTION_SCALE : 1.0);
}

// Intersect in CAMERA-relative space; originRel is subtracted only for the ray/slab intersection.
// Keeping slab bounds in that same space fixes secondary rays whose origin.y is not camera.y.
bool cloudSlabInterval(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                       out float t0, out float t1) {
    float bottom = push.clouds.w - push.cloudAnchor.z * 0.5;
    float top = bottom + push.cloudAnchor.z;
    t0 = 0.0;
    t1 = maxDistance;
    if (abs(dir.y) < 1.0e-5) {
        if (originRel.y <= bottom || originRel.y >= top) return false;
    } else {
        float a = (bottom - originRel.y) / dir.y;
        float b = (top - originRel.y) / dir.y;
        t0 = max(0.0, min(a, b));
        t1 = min(maxDistance, max(a, b));
    }
    return t1 > t0;
}

/** Optical depth toward the active celestial, clipped to the actual slab, with no camera fades. */
float cloudLightOpticalDepth(WorldPush push, float3 posRel, float3 lightDir, int lightSteps,
                            float maxDistance) {
    float t0, t1;
    if (!cloudSlabInterval(push, posRel, lightDir, maxDistance, t0, t1)) return 0.0;
    t1 = min(t1, t0 + push.cloudAnchor.z * CLOUD_MAX_SLAB_CROSSINGS);
    if (lightSteps == 2 && cloudStyle(push) == CLOUD_STYLE_VOLUMETRIC) {
        // With just two points, quadratic strata put 75% of the weight on one distant sample.
        // Two-point Gauss-Legendre covers the full interval more evenly without adding a query;
        // this also avoids coherent light bands when combined with a small view budget.
        float left = lerp(t0, t1, 0.2113248654);
        float right = lerp(t0, t1, 0.7886751346);
        return (cloudOpticalDensity(push, posRel + lightDir * left)
              + cloudOpticalDensity(push, posRel + lightDir * right)) * ((t1 - t0) * 0.5);
    }
    float optical = 0.0;
    for (int i = 0; i < lightSteps; i++) {
        // Quadratic strata resolve nearby occluders better than the old six equal, slab-sized steps.
        // Integrate their real lengths so changing the quality budget does not change extinction.
        float u0 = float(i) / float(lightSteps);
        float u1 = float(i + 1) / float(lightSteps);
        float a = lerp(t0, t1, u0 * u0);
        float b = lerp(t0, t1, u1 * u1);
        optical += cloudOpticalDensity(push, posRel + lightDir * ((a + b) * 0.5)) * (b - a);
    }
    return optical;
}

float cloudLightOpticalDepth(WorldPush push, float3 posRel, float3 lightDir, int lightSteps) {
    return cloudLightOpticalDepth(push, posRel, lightDir, lightSteps, 1.0e9);
}

// A receiver shadow crosses a whole bank, unlike self-shadow from inside one. Quadratic near-point
// strata undersampled the far half and two samples could miss entire banks at low sun. Use bounded,
// uniform spatial intervals instead; Low still halves the vertical budget and filters fine erosion.
float cloudShadowOpticalDepth(WorldPush push, float3 posRel, float3 lightDir) {
    float t0, t1;
    if (!cloudSlabInterval(push, posRel, lightDir, 1.0e9, t0, t1)) return 0.0;
    float thickness = push.cloudAnchor.z;
    t1 = min(t1, t0 + thickness * CLOUD_MAX_SLAB_CROSSINGS);
    CloudQualitySettings quality = cloudQualitySettings(push);
    float stepLen = max(thickness / float(quality.shadowSteps), (t1 - t0) / float(quality.shadowCap));
    int steps = min(int(ceil((t1 - t0) / stepLen)), quality.shadowCap);
    float optical = 0.0;
    for (int i = 0; i < steps; i++) {
        float a = t0 + stepLen * float(i);
        float b = min(t1, a + stepLen);
        if (b <= a) continue;
        optical += cloudOpticalDensity(push, posRel + lightDir * ((a + b) * 0.5)) * (b - a);
        if (optical > 8.0) break; // direct visibility only: residual sun transmission < 0.00034
    }
    return optical;
}

// Sky is not a point sun. Integrate the actual cloud material overhead, even when the local sample is
// on a thin skin below a thick lobe. The old localDensity * distance estimate brightened such skins
// regardless of the occluder above them. Diffuse Low/Medium keep that estimate to bound indirect cost.
float cloudSkyOpticalDepth(WorldPush push, float3 p, float opticalDensity, int skySteps) {
    float localThickness = cloudFormationThickness(push, push.cloudAnchor.xy + p.xz);
    float height = p.y - (push.clouds.w - push.cloudAnchor.z * 0.5);
    float remaining = max(localThickness - height, 0.0);
    if (skySteps == 0) return opticalDensity * remaining * 0.5;
    return cloudLightOpticalDepth(push, p, float3(0.0, 1.0, 0.0), skySteps, remaining);
}

struct CloudScatteringPhases {
    float single;
    float second;
    float third;
    float backWeight;
};

// Phase angles are constant along a ray: evaluate them ONCE, not at every occupied march sample.
CloudScatteringPhases cloudScatteringPhases(float cosT, int multipleOrders) {
    CloudScatteringPhases p = { cloudPhase(cosT), 0.0, 0.0, 1.0 - smoothstep(-0.2, 0.7, cosT) };
    if (multipleOrders >= 1) p.second = 0.30 * hgLobe(cosT, CLOUD_HG_G * 0.5);
    if (multipleOrders >= 2) p.third = 0.10 * hgLobe(cosT, CLOUD_HG_G * 0.25);
    return p;
}

// Neutral droplet scattering, not a screen/sun-distance colour ramp. Direct sun, diffuse sky and
// weak multiple scattering all lose energy with optical depth. The small sky floor represents
// unresolved hemispherical fill, not emission: a cloud with no incident light still emits nothing.
float3 cloudInScatter(float3 sunRadiance, float3 skyRadiance, float sunTau, float skyTau,
                      CloudScatteringPhases phases, int multipleOrders) {
    float sunT = exp(-sunTau);
    // Optical-depth powder term, using the transmittance already calculated (no extra exp).
    float single = sunT * phases.single * (1.0 - 0.25 * phases.backWeight * sunT * sunT);
    float multiple = 0.0;
    if (multipleOrders >= 1) multiple += phases.second * exp(-0.50 * sunTau - 0.15 * skyTau);
    if (multipleOrders >= 2) multiple += phases.third * exp(-0.25 * sunTau - 0.15 * skyTau);
    float skyVisibility = 0.06 + 0.64 * exp(-skyTau);
    return CLOUD_ALBEDO * (sunRadiance * (single + multiple) + skyRadiance * skyVisibility);
}

/** Result of a volumetric march: premultiplied scattered radiance and the surviving transmittance. */
public struct CloudVolume {
    public float3 scatter;
    public float transmittance;
};

/**
 * Ray-march the slab and return in-scattered radiance plus transmittance.
 *
 * Front-to-back emission/absorption integration: at each step the sample's in-scattering (sun radiance,
 * attenuated by the light march, shaped by the HG phase, plus a sky ambient term) is accumulated
 * weighted by how much light still reaches the eye, and the transmittance is decremented by
 * Beer-Lambert. The caller composites with `col * transmittance + scatter`, so the result is correct
 * over any background — including a terrain hit, which is what makes the volumetric style occlude and
 * be occluded correctly.
 */
public CloudVolume cloudMarch(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                              float3 skyBehind, bool highQuality) {
    int marchSteps = highQuality ? CLOUD_MARCH_STEPS : CLOUD_MARCH_STEPS_CHEAP;
    int lightSteps = highQuality ? CLOUD_LIGHT_STEPS : CLOUD_LIGHT_STEPS_CHEAP;
    CloudVolume result;
    result.scatter = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;

    float thickness = max(push.cloudAnchor.z, 1.0);
    bool classic = cloudStyle(push) == CLOUD_STYLE_CLASSIC;
    CloudQualitySettings quality = cloudQualitySettings(push);
    if (!classic) lightSteps = highQuality ? quality.lightSteps : quality.diffuseLightSteps;
    // Keep the legacy fallback's crossing fade, but NEVER dissolve a real volumetric formation
    // around the observer. Bounded slab intersections handle inside/parallel rays without a band.
    float halfThickness = thickness * 0.5;
    float crossFade = classic
            ? smoothstep(halfThickness * 0.5, halfThickness * 1.5, abs(push.clouds.w - originRel.y))
            : 1.0;
    float t0, t1;
    if (crossFade <= 0.0 || !cloudSlabInterval(push, originRel, dir,
                                             min(maxDistance, push.cloudAnchor.w), t0, t1)) {
        return result;
    }
    // Cap how far a single ray may travel INSIDE the slab. A ray looking at the horizon enters the deck
    // at a grazing angle and stays in it for hundreds of blocks, accumulating an optical depth no
    // vertical view ever reaches — which is why distant cloud went solid white even at low opacity and
    // low thickness, while the same cloud overhead looked correct. Real cumulus does not behave like an
    // infinite slab either: you see individual clouds with sky between them, not a continuous wall.
    //
    // Keep the established finite integration budget. This is an approximation: very oblique rays
    // can miss distant banks. Validate the horizon and in-cloud views on GPU before increasing it.
    t1 = min(t1, t0 + thickness * CLOUD_MAX_SLAB_CROSSINGS);
    if (t1 <= t0) {
        return result;
    }

    // Distance fade, matched to the flat style's horizon fade so the two agree about where cloud ends.
    float horizon = length(dir.xz) * t0;
    float fade = 1.0 - smoothstep(push.cloudAnchor.w * 0.55, push.cloudAnchor.w, horizon);
    if (fade <= 0.0) {
        return result;
    }

    float3 sunRadiance = push.lightRadiance.xyz;
    float opacity = clamp(push.clouds.y, 0.0, 1.0);
    bool hasSun = max(sunRadiance.x, max(sunRadiance.y, sunRadiance.z)) > 0.0;
    bool needsSky = max(skyBehind.x, max(skyBehind.y, skyBehind.z)) > 0.0
                 || (hasSun && quality.multipleOrders > 0);
    int multipleOrders = hasSun ? quality.multipleOrders : 0;
    CloudScatteringPhases phases = {};
    if (!classic && hasSun) phases = cloudScatteringPhases(dot(dir, push.lightDir.xyz), multipleOrders);

    float stepLen;
    if (classic) {
        // Preserve the missing-map fallback exactly; the quality selector must not change classic.
        int cap = highQuality ? CLOUD_MARCH_STEPS_MAX : CLOUD_MARCH_STEPS_CHEAP_MAX;
        marchSteps = clamp(int(ceil((t1 - t0) / CLOUD_TARGET_STEP_BLOCKS)), marchSteps, cap);
        stepLen = (t1 - t0) / float(marchSteps);
    } else {
        int baseSteps = highQuality ? quality.viewSteps : quality.diffuseSteps;
        int cap = highQuality ? quality.viewCap : quality.diffuseCap;
        stepLen = max(thickness / float(baseSteps), (t1 - t0) / float(cap));
        marchSteps = min(int(ceil((t1 - t0) / stepLen)), cap);
        // Fixed-width intervals with a shortened last interval: adding/removing a step does NOT
        // relocate all other samples, unlike span / ceil(span / targetStep). The new interval starts
        // with zero optical weight. This avoids budget-boundary pops without animated jitter/history.
    }
    // Vanilla shades cloud faces by ORIENTATION with flat, constant tones — no smooth lighting model.
    // Reproducing that inside the march is what keeps the classic style looking like Minecraft even
    // though it is now a real volume: the box's top is bright, its underside dark, its vertical sides
    // in between, picked from the view direction the same way vanilla picks a quad's face.
    float classicFace = 1.0;
    if (classic) {
        float grazing = 1.0 - abs(dir.y);
        classicFace = dir.y < 0.0 ? 1.0 : 0.52;   // looking down at tops vs up at undersides
        classicFace = lerp(classicFace, 0.78, grazing);
    }

    for (int i = 0; i < marchSteps; i++) {
        float a = t0 + stepLen * float(i);
        float b = min(t1, a + stepLen);
        if (b <= a) continue;
        float distance = classic ? t0 + stepLen * (float(i) + 0.5) : (a + b) * 0.5;
        float opticalDensity;
        if (!classic && highQuality && quality.densityProbes > 1) {
            // Two-point Gauss-Legendre extinction; avoid sampling every curved edge at the same
            // midpoint phase. Lighting stays once per interval at the material-weighted centroid.
            // Count these as TWO real density samples, not a cosmetic "8 samples" quality label.
            float left = lerp(a, b, 0.2113248654);
            float right = lerp(a, b, 0.7886751346);
            float sigmaLeft = cloudOpticalDensity(push, originRel + dir * left);
            float sigmaRight = cloudOpticalDensity(push, originRel + dir * right);
            opticalDensity = (sigmaLeft + sigmaRight) * 0.5;
            distance = lerp(left, right, sigmaRight / max(sigmaLeft + sigmaRight, 1.0e-20));
        } else {
            opticalDensity = cloudOpticalDensity(push, originRel + dir * distance);
        }
        float3 p = originRel + dir * distance;
        if (opticalDensity <= 0.0) {
            continue;
        }
        float interval = classic ? stepLen : b - a;
        float sampleTransmittance = exp(-opticalDensity * interval * fade * crossFade);
        float lightTau = (classic || hasSun)
                ? cloudLightOpticalDepth(push, p, push.lightDir.xyz, lightSteps) : 0.0;
        float3 inScatter;
        if (classic) {
            // Flat, vanilla-style face tone. Self-shadowing is deliberately damped (mixed only 40% in)
            // so cloud boxes keep the poster-flat look instead of developing soft volumetric gradients.
            float shade = lerp(1.0, exp(-lightTau), 0.4);
            inScatter = push.cloudColor.xyz * CLOUD_INV_PI
                    * (sunRadiance * classicFace * shade * 3.0 + skyBehind * 1.4);
        } else {
            int skySteps = highQuality ? quality.skySteps : quality.diffuseSkySteps;
            float skyTau = needsSky ? cloudSkyOpticalDepth(push, p, opticalDensity, skySteps) : 0.0;
            inScatter = (hasSun || needsSky)
                    ? cloudInScatter(sunRadiance, skyBehind, lightTau, skyTau, phases, multipleOrders)
                    : float3(0.0, 0.0, 0.0);

        }
        // Energy-conserving accumulation: integrate in-scatter over the step's absorbed fraction.
        result.scatter += result.transmittance * inScatter * (1.0 - sampleTransmittance);
        result.transmittance *= sampleTransmittance;
        if (result.transmittance < 0.001) {
            // Keep the small residual: snapping T to zero created a visible 1% discontinuity when
            // a moving ray just crossed the old early-out threshold. Remaining energy is < 0.1%.
            break;
        }
    }

    // ---- Opacity as a genuine ceiling, applied ONCE to the finished march.
    //
    // It used to scale the extinction, which is per unit length — so the total optical depth still grew
    // with the slab depth and with the path length through it. That is why a low opacity still went
    // fully solid at 100% thickness, and why even a thin deck turned opaque toward the horizon, where a
    // grazing ray traverses hundreds of blocks of slab. The slider had no ceiling at all.
    //
    // Capping the finished result instead makes it exactly a blend between "no cloud" and "this cloud":
    // at 20% the cloud can never be more than 20% opaque, at any thickness, at any view angle. Scatter
    // is premultiplied so it scales by the same factor, which keeps
    // `background * transmittance + scatter` energy-correct.
    result.scatter *= opacity;
    result.transmittance = 1.0 - (1.0 - result.transmittance) * opacity;
    return result;
}

// ---- Unified entry point -------------------------------------------------------------------------

/**
 * Ambient sky fill for cloud shading when the real sky radiance is not available.
 *
 * The miss shader can pass the actual atmosphere result, but a ray that ends on terrain has no sky
 * value to hand — so this derives a cheap stand-in from the celestial radiance the frame is already
 * lit by. It follows the same day/night and weather curve as everything else, and keeps a small floor
 * so clouds at night are dark grey rather than black.
 */
public float3 cloudSkyAmbient(WorldPush push) {
    float3 sun = push.lightRadiance.xyz;
    return sun * 0.10 + float3(0.012, 0.017, 0.028);
}

/**
 * Clouds crossed along ONE ray segment, as premultiplied scattered radiance plus the transmittance the
 * background keeps. Both styles answer in this same form.
 *
 * This shape is deliberate: callers composite clouds with two lines
 * ({@code L += throughput * scatter; throughput *= transmittance;}), and being a premultiplied
 * participating-medium term inside the path integral is what gives clouds correct behaviour over
 * reflections, refractions and indirect bounces for free.
 *
 * `maxDistance` is the distance to the nearest scene hit along the ray (or a large value when the ray
 * escapes to the sky). Bounding the cloud by it is the fix for the "world shows through the clouds"
 * bug — see cloudSegmentNote below.
 */
public CloudVolume cloudSegment(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                                float3 ambient, bool highQuality) {
    CloudVolume result;
    result.scatter = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;
    if (!cloudsEnabled(push)) {
        return result;
    }
    // Three physical decks, picked by style and thickness:
    //   * classic with the authored map: ANALYTIC boxes — vanilla's exact cell geometry intersected
    //     directly, no march at all (cloudClassicBoxes). This is the normal classic path;
    //   * volumetric (and the classic map-unavailable fallback): the ray-marched slab;
    //   * retain the legacy flat-sheet path for near-zero slabs. The host's volumetric range never
    //     selects it (minimum 16), nor do normal classic settings (minimum 4).
    bool classic = cloudStyle(push) == CLOUD_STYLE_CLASSIC;
    if (push.cloudAnchor.z > CLOUD_FLAT_EPSILON) {
        if (classic && push.cloudCellsAddr != 0u) {
            return cloudClassicBoxes(push, originRel, dir, maxDistance, ambient);
        }
        return cloudMarch(push, originRel, dir, maxDistance, ambient, highQuality);
    }
    CloudHit h = cloudTrace(push, originRel, dir, maxDistance);
    if (!h.hit) {
        return result;
    }
    float alpha = cloudAlpha(push, h, cloudCoverage(push, h.samplePos));
    if (alpha <= 0.0) {
        return result;
    }
    // The flat sheet keeps each style's look: the authored colour for classic, the volumetric albedo
    // for a legacy volumetric flat-sheet query.
    float3 albedo = classic ? push.cloudColor.xyz : CLOUD_ALBEDO;
    float grazing = 1.0 - abs(dir.y);
    float faceLight = h.fromBelow ? 0.52 : 1.0;
    faceLight = lerp(faceLight, 0.78, grazing);
    // Premultiplied, so `background * transmittance + scatter` reproduces the lerp exactly.
    result.scatter = cloudRadianceClassic(push, faceLight, albedo, ambient) * alpha;
    result.transmittance = 1.0 - alpha;
    return result;
}

// THE BUG THIS FIXES. Clouds used to be composited only inside world.rmiss, which runs only when a ray
// hits NOTHING. Flying above the deck and looking down, the ray hits the ground — a hit, not a miss —
// so the clouds lying between the camera and that ground were never drawn at all and the world showed
// straight through them. Routing every consumer through cloudSegment with the real hit distance fixes
// both halves of that: cloud in FRONT of a surface is now drawn over it, and cloud BEHIND a surface is
// correctly rejected by the maxDistance test instead of being painted on top.

/** Composite clouds over a background in one call, for callers that hold a finished colour. */
public float3 cloudLayer(WorldPush push, float3 originRel, float3 dir, float maxDistance,
                         float3 background, float3 ambient, bool highQuality) {
    CloudVolume v = cloudSegment(push, originRel, dir, maxDistance, ambient, highQuality);
    return background * v.transmittance + v.scatter;
}
