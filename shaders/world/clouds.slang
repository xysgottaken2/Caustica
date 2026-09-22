// Cloud deck — two selectable styles over one shared field.
//
// Caustica cancels vanilla's LevelRenderer outright (see LevelRendererMixin), and vanilla's cloud pass
// lives inside it — so under the ray-traced renderer there were simply no clouds in the world at all.
// The sky shader modelled an atmosphere, a sun, a moon and a starfield, and the weather code even
// hid the celestial discs "behind the cloud deck" during rain, but nothing ever drew that deck. This
// module is that deck.
//
// TWO STYLES, TWO SHAPE SOURCES. Where clouds are is answered differently per style, and cloudCoverage
// is the one place both answers live — so the visible deck and the shadow it casts always agree.
//   * CLOUD_STYLE_CLASSIC — vanilla's blocky clouds, from vanilla's own SHAPE: the clouds.png cell map
//     (one 12-block cell per pixel, bit-packed into the BDA ring by RtComposite). With real thickness
//     the deck is intersected ANALYTICALLY — each occupied cell is an axis-aligned box and the ray walks
//     the cell grid to the first one (cloudClassicBoxes), which is exact and needs no march at all.
//     Colour is EnvironmentAttributes.CLOUD_COLOR — the game's own per-weather value — so a storm greys
//     the deck with no hand-tuned ramp. At zero thickness it collapses to the flat sheet. If the map is
//     unavailable the style degrades to the old noise-quantised deck rather than to nothing.
//   * CLOUD_STYLE_VOLUMETRIC — billowy, photoreal cumulus. cloudCoverageField (three octaves of value
//     noise) drives a domain-warped fBm density with a cauliflower erosion profile, marched with
//     light-marched self-shadowing, Beer-Lambert extinction, a dual-lobe Henyey-Greenstein phase and a
//     powder (dark-edge) term. Its field and shape are deliberately untouched by the classic rework.
//
// Both styles honour the same thickness slider; the weather fill (cloudColor.w) closes either sky
// completely in a storm.
//
// DEPTH-CORRECT AGAINST THE WORLD. Every entry point that produces visible cloud takes the distance to
// the nearest scene hit and stops there, so terrain in front of a cloud occludes it. Getting this wrong
// is exactly the bug where the world appeared to punch through the deck from above (see cloudLayer).
//
// Only world_common is imported: world.rmiss must NOT pull in world_core (that module declares the
// raygen bindings), so every entry point here takes the WorldPush explicitly, exactly like
// world_common's own worldFeature/worldFlag accessors.

import world_common;

// ---- Pattern geometry. The field is EXACTLY periodic over CLOUD_CELL_BLOCKS * CLOUD_PERIOD_CELLS
// blocks (6144), because cloudHash wraps its cell index to that period. That is load-bearing, not
// decoration: RtComposite pushes a sample anchor already reduced modulo the same 6144 (both to fold in
// the wind scroll, which grows without bound with world time, and to keep the anchor a small float far
// from a Minecraft world border's 30M-block coordinates). Wrapping an aperiodic noise would teleport
// the whole sky the moment the anchor rolled over; wrapping a periodic one is seamless. The repeat is
// four times the deck's own view limit, so it is never visible in one frame.
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

// ---- Volumetric march budget.
//
// This is the whole cost of the volumetric style, and it multiplies by SPP and by path depth, so it is
// split into two tiers rather than being one flat number:
//   * FULL is used where the clouds are actually looked at — the camera ray and specular/mirror bounces
//     (reflections of the sky in water and glass). This is what the player sees, so it gets the light
//     march and the full step count.
//   * CHEAP is used for diffuse indirect bounces. Those contribute a broad, low-frequency sky-fill term
//     that gets averaged over the hemisphere and then denoised; resolving cloud detail there is
//     invisible in the result but would multiply the cost by the bounce count. It keeps the same
//     extinction and phase, so the ENERGY stays right — only the detail is coarser.
// Quality comes from the light march and the phase function rather than raw step count.
public static const int   CLOUD_MARCH_STEPS = 24;
public static const int   CLOUD_LIGHT_STEPS = 6;
public static const int   CLOUD_MARCH_STEPS_CHEAP = 6;
public static const int   CLOUD_LIGHT_STEPS_CHEAP = 2;
// Upper bounds used when the step count is scaled to the path length (see cloudMarch). A fixed step
// COUNT means a deeper slab silently gets coarser steps, which bands: cloud lobes are tens of blocks
// wide, so a 60-block step walks straight past them. These cap the cost of that scaling.
public static const int   CLOUD_MARCH_STEPS_MAX = 48;
public static const int   CLOUD_MARCH_STEPS_CHEAP_MAX = 12;
// Target distance between samples. Comfortably finer than a lobe, so the march resolves shape.
public static const float CLOUD_TARGET_STEP_BLOCKS = 6.0;
public static const float CLOUD_EXTINCTION = 0.115; // per block, at density 1
// Optical depth is normalised against this thickness, so making the deck taller adds VOLUME without
// making it more opaque. Without it, tau = density * sigma * pathLength grows with the slab depth and
// the thickness slider silently doubles as a second opacity slider.
public static const float CLOUD_REFERENCE_THICKNESS = 40.0;
// Longest in-slab path a single ray may integrate, in slab depths. Bounds the grazing/horizon case
// (see cloudMarch); a few crossings already saturate a real cloud.
public static const float CLOUD_MAX_SLAB_CROSSINGS = 3.5;
// Horizontal scale of the volumetric field, relative to the classic 12-block cell grid. Below 1 the
// lobes get WIDER (the field is sampled more slowly), which is what lets cloud width grow together with
// the extra height so the result reads as heaped cumulus rather than a stretched sheet.
//
// MUST be a reciprocal power of two, and CLOUD_FIELD_PERIOD_BLOCKS (Java) must be a whole multiple of
// CLOUD_CELL_BLOCKS * CLOUD_PERIOD_CELLS / this. The volumetric field samples at samplePos * SCALE, so
// the anchor wrap is only seamless if the wrap distance is an exact multiple of the hash period IN THAT
// SCALED SPACE. At 0.45 it was not: every wrap landed 230.4 cells off and the whole cloudscape snapped
// to a different pattern — visible as clouds abruptly changing shape while walking, in the volumetric
// style only (classic samples unscaled, so it always wrapped cleanly). A power of two also makes the
// multiply exact in binary floating point, so the identity holds bit-for-bit rather than approximately.
public static const float CLOUD_VOLUMETRIC_SCALE = 0.5;
// Octave divisors for the warp and the two billow layers. ALL must be powers of two, for the same
// reason CLOUD_VOLUMETRIC_SCALE must be: each is another space the periodic hash is sampled in, and the
// anchor wrap has to be a whole hash period in every one of them simultaneously. The largest divisor is
// the binding constraint on CLOUD_FIELD_PERIOD_BLOCKS (Java): period >= 512 * CELL * maxDiv / SCALE.
// These were 2.0 / 0.9 / 0.35, and the two non-power-of-two ones desynced the detail layers at every
// wrap even after the base field was fixed — the same pop, just from the erosion instead of the shape.
public static const float CLOUD_WARP_DIV = 2.0;
public static const float CLOUD_BILLOW_DIV_COARSE = 1.0;
public static const float CLOUD_BILLOW_DIV_FINE = 0.25;
// How much wider a cloud gets around its belly than at its base/crown. Turns a straight-sided extrusion
// into a rounded, bulging mass — see the note in cloudVolumeDensity.
public static const float CLOUD_BULGE = 0.30;
public static const float CLOUD_HG_G = 0.72;        // forward-scattering anisotropy of cloud droplets
public static const float CLOUD_HG_BACK = -0.28;    // weak backward lobe (see cloudPhase)
public static const float CLOUD_POWDER_STRENGTH = 0.7; // dark-edge / multiple-scattering approximation
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
 * This is the shared truth both styles and the shadow query are built on.
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
        bool filled = cloudHash(wrapped + int2(127, 61)) < fill;
        return (authored || filled) ? 1.0 : 0.0;
    }
    float coverage = retain + (1.0 - retain) * fill;
    if (coverage >= 1.0) {
        return 1.0;
    }
    float threshold = 1.0 - clamp(coverage, 0.0, 1.0);
    return smoothstep(threshold, threshold + CLOUD_EDGE_SOFTNESS, cloudCoverageField(samplePos));
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
 * This is the "Cloud Shadows" slider, and it is an analytic plane intersection plus one coverage
 * evaluation — no march, no extra ray, no added variance for the denoiser to chase, for either style.
 *
 * It samples the COVERAGE map at the deck's mid-plane, not the eroded 3D density, so for the volumetric
 * style the shadow follows the cloud's overall footprint rather than its individual billows. That is a
 * deliberate trade: matching per-lobe would mean marching the slab for every shading point of every
 * path vertex, and the difference is a slightly softer shadow edge that is indistinguishable in motion
 * from the real thing.
 * Points above the deck get t <= 0 and are unshadowed for free, which is the correct answer rather than
 * a special case.
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
    if (t0 <= 0.0 && cloudCellOccupied(push, cell)) {
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
        if (cloudCellOccupied(push, cell)) {
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
                if (cloudCellOccupied(push, cell)) {
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

/**
 * Powder / dark-edge term. Multiple scattering makes the dense INTERIOR of a cloud brighter than its
 * thin edges when lit from behind the viewer — the opposite of what single-scattering alone predicts,
 * and its absence is why cheap volumetric clouds look like flat grey cotton. Approximated from local
 * density, which is the standard Frostbite/Horizon trick.
 */
float cloudPowder(float density) {
    return 1.0 - exp(-density * 4.0);
}

/**
 * 3D density inside the slab, per style.
 *
 * CLASSIC is a box: the density is the quantised cell coverage, constant through the whole thickness
 * with only a hair of taper at the very top and bottom faces. That is deliberately NOT billowy — it is
 * what makes the marched result read as vanilla's solid cuboid clouds rather than a soft blob, and it
 * is why the classic style still looks like Minecraft once it has real height.
 *
 * VOLUMETRIC is cumulus. Three things do the work, and the previous version had none of them, which is
 * why it looked like a thin sheet:
 *   * a CAULIFLOWER height profile — the cloud bulges outward toward its middle and rounds off at the
 *     top, instead of tapering symmetrically like a lens. Real cumulus has a flat-ish base and a
 *     billowing crown, so the profile is asymmetric on purpose;
 *   * DOMAIN WARPING — the sample position is displaced by a low-frequency noise before the detail
 *     octaves are read, which turns straight, gridded edges into curling, rounded lobes. This is the
 *     single biggest contributor to a cloud reading as "puffy" rather than "noisy";
 *   * a coverage BOOST toward the core, so the middle of a cloud is genuinely opaque. A field that only
 *     ever reaches ~0.5 density can never look thick no matter how deep the slab is.
 */
float cloudVolumeDensity(WorldPush push, float3 posRel, float heightFrac) {
    float2 samplePos = push.cloudAnchor.xy + posRel.xz;
    float hf = clamp(heightFrac, 0.0, 1.0);

    if (cloudStyle(push) == CLOUD_STYLE_CLASSIC) {
        float coverage = cloudCoverage(push, samplePos);
        if (coverage <= 0.0) {
            return 0.0;
        }
        // Near-square vertical profile: full density across the body, clipped hard at both faces so the
        // silhouette from the side is a rectangle, matching vanilla's cloud boxes.
        float faces = smoothstep(0.0, 0.06, hf) * smoothstep(0.0, 0.06, 1.0 - hf);
        return coverage * faces;
    }

    // ---- Volumetric cumulus.
    //
    // The goal here is HEAPED cloud — thick, wide, vertically developed, the way real cumulus and
    // Photon-style shader clouds look. The previous version was flat for a measurable reason: its lobes
    // were ~24 blocks across but only ~14 blocks of real density tall (a 1.7:1 layer), because the
    // profile faded out over the top 65% of the slab. Real cumulus is roughly 1:1 to 1:2 width:height.
    // Three changes fix that, and they are all about the aspect ratio rather than about adding detail:
    //   * the body now holds full density across most of the slab and only rounds off at the very top,
    //     so the cloud actually USES the height the thickness slider gives it;
    //   * the horizontal field is sampled at a larger scale, making lobes broader and puffier instead
    //     of small and busy — width and height grow together, so it reads as heaped, not stretched;
    //   * erosion is concentrated at the extreme top and bottom, leaving the middle solid, which is
    //     what gives a cloud a dense core with a cauliflower crown rather than uniform fluff.
    float base = cloudCoverageField(samplePos * CLOUD_VOLUMETRIC_SCALE);
    float threshold = 1.0 - clamp(push.clouds.x, 0.0, 1.0);
    float coverage = smoothstep(threshold, threshold + CLOUD_EDGE_SOFTNESS, base);
    if (coverage <= 0.0) {
        return 0.0;
    }

    // Vertical development. A cumulus has a hard flat base (condensation level), a tall body that stays
    // dense, and a rounded crown. The top fade starts high (0.62) so the body occupies most of the slab
    // instead of tapering away from the middle — this is the single change that makes the cloud thick.
    // Taller clouds also get to be taller: coverage scales how far up the crown reaches, so dense
    // regions billow upward into towers while thin edges stay low and flat, exactly like the real sky.
    float towerTop = lerp(0.35, 1.0, coverage);
    float bottom = smoothstep(0.0, 0.10, hf);
    float top = 1.0 - smoothstep(towerTop * 0.62, towerTop, hf);
    float profile = clamp(bottom * top, 0.0, 1.0);

    // BULGE. Without this the cloud is a vertical extrusion with straight sides — tall, but reading as a
    // wall rather than as heaped cloud. Real cumulus is widest around its lower-middle and narrows
    // toward both the base and the crown, so the coverage THRESHOLD is relaxed there and tightened at
    // the extremes: the same cloud grows sideways in its belly and pulls in at top and bottom. That
    // rounded silhouette is most of what makes the Photon-style clouds look voluminous.
    float belly = 1.0 - abs(hf - 0.42) * 2.0;
    coverage = clamp(coverage + smoothstep(0.0, 1.0, max(belly, 0.0)) * CLOUD_BULGE - CLOUD_BULGE * 0.35,
                     0.0, 1.0);
    if (coverage <= 0.0) {
        return 0.0;
    }

    // Domain warp, amplitude growing with height: bases stay flat, crowns curl into rounded lobes.
    float2 warpUv = samplePos * CLOUD_VOLUMETRIC_SCALE / (CLOUD_CELL_BLOCKS * CLOUD_WARP_DIV);
    float2 warp = float2(cloudNoise(warpUv), cloudNoise(warpUv + float2(41.0, 17.0))) - 0.5;
    float2 warped = samplePos * CLOUD_VOLUMETRIC_SCALE
            + warp * CLOUD_CELL_BLOCKS * (1.0 + 3.0 * hf);

    // Billow detail: inverted noise (1 - |2n-1|) gives rounded cellular lobes rather than a cloudy blur,
    // which is what reads as cauliflower. Scales chosen so the lobes are a fraction of a cloud's width,
    // not a fraction of a cell — small lobes on a big cloud is exactly the cumulus look.
    float d1 = cloudNoise(warped / (CLOUD_CELL_BLOCKS * CLOUD_BILLOW_DIV_COARSE));
    float d2 = cloudNoise(warped / (CLOUD_CELL_BLOCKS * CLOUD_BILLOW_DIV_FINE) + float2(19.0, 7.0));
    float billow = (1.0 - abs(d1 * 2.0 - 1.0)) * 0.7 + (1.0 - abs(d2 * 2.0 - 1.0)) * 0.3;

    // Erosion concentrated at the extremes: the crown breaks into lobes and the base frays, but the
    // middle of the cloud stays solid. Uniform erosion is what makes procedural clouds read as a flat volume.
    float extremes = smoothstep(0.55, 1.0, hf) * 0.85 + smoothstep(0.18, 0.0, hf) * 0.5;
    float edge = 1.0 - coverage;
    float erosion = billow * (edge * 0.55 + extremes * 0.45);

    // Dense core: cumulus interiors are optically thick, and a field that peaks at ~0.5 can never look
    // like anything but haze no matter how deep the slab is.
    float density = coverage * (0.85 + 0.75 * coverage) - erosion;
    return clamp(density, 0.0, 1.0) * profile;
}

/** Optical depth from a point toward the light, for self-shadowing inside the slab. */
float cloudLightTransmittance(WorldPush push, float3 posRel, float3 lightDir,
                              float slabBottom, float slabTop, int lightSteps) {
    float thickness = max(slabTop - slabBottom, 0.1);
    // March a short distance toward the light; step so the march covers roughly the slab's own depth,
    // which is the scale self-shadowing operates on. Fewer steps cover the same distance with longer
    // strides, so the transmittance stays in the right range instead of the march falling short.
    float stepLen = thickness / (float(lightSteps) * max(abs(lightDir.y), 0.35));
    float optical = 0.0;
    for (int i = 0; i < lightSteps; i++) {
        float3 p = posRel + lightDir * (stepLen * (float(i) + 0.5));
        if (p.y < slabBottom || p.y > slabTop) {
            break; // left the slab: nothing further can shadow this sample
        }
        optical += cloudVolumeDensity(push, p, (p.y - slabBottom) / thickness) * stepLen;
    }
    return exp(-optical * CLOUD_EXTINCTION);
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
    // Crossing EXCLUSION. The flat path fades the deck out as the camera passes through its nominal
    // thickness (cloudTrace's crossFade) so the transition reads as dissolving cloud instead of a
    // one-pixel flip between lit top and dark underside. The march had no equivalent: with the camera
    // sitting near the deck plane, near-horizontal rays start INSIDE the slab and integrate a whole
    // slab-crossing of in-scatter from the first step, which painted a bright horizontal band around
    // the camera tracking it everywhere — the reported "line" that brightens everything nearby. The
    // fix is not another fade of coverage (coverage fading still lets the march accumulate light); the
    // slab itself is optically excluded inside the crossing region: sigma scales by the same crossFade
    // curve the flat path uses, so both extinction AND the in-scatter it feeds collapse together, and
    // at the mid-plane the deck contributes exactly nothing while the camera crosses it.
    float deckRel = push.clouds.w - originRel.y;
    float halfThickness = max(thickness, 0.5) * 0.5;
    float crossFade = smoothstep(halfThickness * 0.5, halfThickness * 1.5, abs(deckRel));
    if (crossFade <= 0.0) {
        return result;
    }
    float slabBottom = push.clouds.w - thickness * 0.5 - originRel.y;
    float slabTop = push.clouds.w + thickness * 0.5 - originRel.y;

    // Slab entry/exit along the ray. A near-horizontal ray inside the slab would march forever, so it
    // is bounded by the view limit like every other ray.
    float t0, t1;
    if (abs(dir.y) < 1.0e-4) {
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
    // Cap how far a single ray may travel INSIDE the slab. A ray looking at the horizon enters the deck
    // at a grazing angle and stays in it for hundreds of blocks, accumulating an optical depth no
    // vertical view ever reaches — which is why distant cloud went solid white even at low opacity and
    // low thickness, while the same cloud overhead looked correct. Real cumulus does not behave like an
    // infinite slab either: you see individual clouds with sky between them, not a continuous wall.
    //
    // A few slab depths is enough to fully develop a cloud, so clamping here costs nothing in the
    // near-field and removes the horizon wall entirely.
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
    float cosT = dot(dir, push.lightDir.xyz);
    float phase = cloudPhase(cosT);
    float opacity = clamp(push.clouds.y, 0.0, 1.0);
    // Scale the step count to the distance actually being marched, so a thick deck is sampled as finely
    // as a thin one instead of just taking longer strides. Clamped both ways: never coarser than the
    // base count, never more expensive than the cap.
    int stepCap = highQuality ? CLOUD_MARCH_STEPS_MAX : CLOUD_MARCH_STEPS_CHEAP_MAX;
    marchSteps = clamp(int((t1 - t0) / CLOUD_TARGET_STEP_BLOCKS), marchSteps, stepCap);
    float stepLen = (t1 - t0) / float(marchSteps);
    bool classic = cloudStyle(push) == CLOUD_STYLE_CLASSIC;
    // Classic clouds are opaque boxes, not haze: they need a much higher extinction so a box saturates
    // within its own thickness instead of reading as translucent haze. Vanilla's clouds you cannot see
    // through, and that is the property being reproduced.
    // Extinction is normalised by the slab depth: a 100-block bank and a 10-block sheet reach the same
    // optical depth straight down, so THICKNESS controls shape and OPACITY controls see-through, with
    // no cross-talk. opacity is deliberately NOT folded in here — see the cap after the march.
    float sigma = CLOUD_EXTINCTION * (classic ? CLOUD_CLASSIC_EXTINCTION_SCALE : 1.0)
            * (CLOUD_REFERENCE_THICKNESS / max(thickness, 1.0)) * fade * crossFade;
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
        float3 p = originRel + dir * (t0 + stepLen * (float(i) + 0.5));
        float heightFrac = (p.y - slabBottom) / max(slabTop - slabBottom, 1.0e-3);
        float density = cloudVolumeDensity(push, p, clamp(heightFrac, 0.0, 1.0));
        if (density <= 0.0) {
            continue;
        }
        float extinction = density * sigma * stepLen;
        float sampleTransmittance = exp(-extinction);
        float lightT = cloudLightTransmittance(push, p, push.lightDir.xyz, slabBottom, slabTop,
                                               lightSteps);
        float3 inScatter;
        if (classic) {
            // Flat, vanilla-style face tone. Self-shadowing is deliberately damped (mixed only 40% in)
            // so cloud boxes keep the poster-flat look instead of developing soft volumetric gradients.
            float shade = lerp(1.0, lightT, 0.4);
            inScatter = push.cloudColor.xyz * CLOUD_INV_PI
                    * (sunRadiance * classicFace * shade * 3.0 + skyBehind * 1.4);
        } else {
            // Direct sun scattering + an ambient sky term so the shadowed interior is grey rather than
            // black. Powder brightens dense interiors (multiple-scattering approximation).
            float powder = lerp(1.0, cloudPowder(density), CLOUD_POWDER_STRENGTH);
            inScatter = CLOUD_ALBEDO
                    * (sunRadiance * lightT * phase * powder * 4.0 + skyBehind * 0.35);
        }
        // Energy-conserving accumulation: integrate in-scatter over the step's absorbed fraction.
        result.scatter += result.transmittance * inScatter * (1.0 - sampleTransmittance);
        result.transmittance *= sampleTransmittance;
        if (result.transmittance < 0.01) {
            result.transmittance = 0.0;
            break; // fully saturated; further steps cannot contribute
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
    //   * a (near) zero thickness collapses to the flat sheet, which is what that slider setting asks
    //     for. It shades like the classic deck of whichever style is active.
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
    // for the volumetric style (whose field and shading are otherwise untouched).
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
