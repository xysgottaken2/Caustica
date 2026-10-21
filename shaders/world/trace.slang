// Ray dispatch: SBT/cull constants, payload construction, ordinary/reordered radiance, guide probes,
// and shadow visibility. Depends on core.

// receiver billboards only. The first-person camera owner uses 0x01 (secondary only).

import world_common;
import world_core;

public static const uint CULL_SECONDARY = 0x01u;
public static const uint CULL_PRIMARY = 0x02u;
// Bit 3 belongs to the ray-traced first-person viewmodel and to nothing else: every other instance mask in
// the world starts at 0xF7 (see RtEntities.MASK_ALL / RtAccel.Instance's default), so a trace with exactly
// this mask finds the hand and no terrain, entity, particle or LOD geometry. That reservation is what makes
// the viewmodel a viewmodel: the camera reaches it only through traceViewmodelOverlay below, and shadow, GI,
// reflection and held-light rays — which never carry this bit — cannot hit it at all, so it casts no shadow
// and never hides what is behind it.
public static const uint CULL_VIEWMODEL = 0x08u;
// Shadow-ray mask for the analytic held-item light: ordinary occluders MINUS the first-person
// viewmodel. Instance masks in play: terrain/entities/block entities 0xF7, DH LOD 0x03, particles
// 0x02, the first-person player 0x01. Bits 2 and 4..7 keep every 0xF7 occluder while matching neither
// the viewmodel (0x01) nor particles (0x02); DH LOD drops out too, but that geometry sits beyond
// the vanilla render distance, where the short-range held light neither reaches nor gets blocked
// by it. Without the exclusion the captured arm/item floats a phantom shadow inside the very beam
// it casts (the visible hand is vanilla's screen-space overlay, not the captured pose). Bit 3 joins that
// exclusion list for free: it is the ray-traced viewmodel's own bit, and a held block must not shade the
// hand holding it either.
public static const uint CULL_HAND_LIGHT = 0xF4u;
// Shadow rays fired from a viewmodel surface. Terrain and ordinary entities still occlude (0xF7 matches),
// but the first-person body (0x01), particles (0x02), the hand's own geometry (0x08) and DH LODs (0x03) do
// not — otherwise the arm the camera is sitting inside throws a hard shadow across the item in it, and the
// held block shadows the fingers holding it. Selected by visibility() whenever the surface being shaded
// carries PAYLOAD_VIEWMODEL, which is how "ignore the player's own shadow when shading the viewmodel" is
// enforced without threading a flag through every lighting and fog call site.
public static const uint CULL_VIEWMODEL_SHADOW = 0xF0u;
public static const uint TERRAIN_BUCKETS = 4u;
public static const uint SBT_RADIANCE = 0u;
public static const uint SBT_SHADOW = TERRAIN_BUCKETS;
public static const uint SBT_STRIDE_BUCKET = 1u;
public static const uint MISS_RADIANCE = 0u;
public static const uint MISS_GUIDE = 1u;

public RayDesc makeRay(float3 origin, float tmin, float3 dir, float tmax) {
    RayDesc r;
    r.Origin = origin;
    r.TMin = tmin;
    r.Direction = dir;
    r.TMax = tmax;
    return r;
}

public float3 offsetSurfaceOrigin(float3 position, float3 surfaceNormal,
                           float3 outgoingDirection, float bias) {
    float3 sideNormal = dot(surfaceNormal, outgoingDirection) >= 0.0
            ? surfaceNormal : -surfaceNormal;
    return position + sideNormal * bias;
}

// A radiance payload carrying only the two words the invoked hit/miss shader READS: `flags`
// (show-celestial gate, consumed by world.rmiss) and the packed ray cone (consumed by world.rchit for
// texture LOD). Every other member is an OUTPUT that world.rchit/world.rmiss write, so it carries no
// information into a trace and is initialized to a neutral value here.
public Payload makeRadiancePayload(uint flags, uint rayCone) {
    Payload p;
    p.albedo = half3(0.0h, 0.0h, 0.0h);
    p.normal = half3(0.0h, 0.0h, 0.0h);
    p.hitT = -1.0; // miss sentinel: world.rmiss writes only albedo, so a miss leaves this negative
    p.motionPrev = half3(0.0h, 0.0h, 0.0h);
    p.f0 = half3(0.0h, 0.0h, 0.0h);
    p.flags = flags;
    p.roughMetal = 0u;
    p.emissionSss = 0u;
    p.iorTransmission = 0u;
    p.rayCone = rayCone;
    return p;
}

// Ordinary radiance trace for latency-sensitive, coherent work such as Pass A. It invokes hit/miss
// shaders directly and therefore requires no invocation-reorder capability.
public void traceRadiance(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                    bool showCelestial, bool rayInWater, float rayConeWidth, float rayConeSpread) {
    // `rayInWater` is the CURRENT medium's water state: the miss shader uses it to keep the above-water
    // cloud deck and the lightning bolt out of rays that have not left the water column yet.
    uint flags = (showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u)
            | (rayInWater && worldPush.underwaterEffects.w > 0.0 ? PAYLOAD_RAY_IN_WATER : 0u);
    payload = makeRadiancePayload(flags, packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), payload);
}

// Auxiliary RR probes deliberately use ordinary TraceRay. They are one-off, incoherent rays whose
// result is consumed immediately, so paying a reorder barrier and carrying guide state across it only
// adds latency. The dedicated miss record also avoids the atmosphere march: guides need hit-vs-miss,
// not sky radiance.
public void traceGuide(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                float rayConeWidth, float rayConeSpread) {
    payload = makeRadiancePayload(0u, packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask, SBT_RADIANCE, SBT_STRIDE_BUCKET,
            MISS_GUIDE, makeRay(ro, tmin, rd, tmax), payload);
}

// Above-blocks layer for the ray-traced first-person viewmodel, called on primary rays only.
//
// Vanilla's hand pass gets "always in front of the world" from a depth clear; a closest-hit shader has no
// such lever, so a block the arm is swinging through would win the hit and punch a hole in the hand.
// Instead, any primary ray that stopped nearer than the viewmodel's own reach (or missed entirely, which is
// where the hand is most visible) is re-run against viewmodel geometry only. A hit there replaces the world
// result — the hand's shading, its motion vector and its material all come from the traced surface now —
// and a miss restores exactly what the world trace had written, so pixels without the hand pay nothing but
// this check.
//
// The trace is bounded by the reach in both directions: tmax is the reach itself, so no BVH work happens
// beyond it, and the mask admits a single small instance. It ends in MISS_GUIDE rather than MISS_RADIANCE
// because the atmosphere march's result would be discarded on a miss, and because the guide miss leaves the
// hitT sentinel negative — which is precisely how the restore below detects "no hand along this ray".
public void traceViewmodelOverlay(float3 ro, float3 rd) {
    float reach = worldPush.viewmodel.x;
    if (reach <= 0.0 || (payload.flags & PAYLOAD_VIEWMODEL) != 0u) {
        return;
    }
    float hitT = payload.hitT;
    if (hitT >= reach) {
        return;
    }
    Payload worldHit = payload;
    uint rayCone = worldHit.rayCone;
    payload = makeRadiancePayload(0u, rayCone);
    TraceRay(topLevelAS, RAY_FLAG_NONE, CULL_VIEWMODEL, SBT_RADIANCE, SBT_STRIDE_BUCKET,
            MISS_GUIDE, makeRay(ro, 0.0, rd, reach), payload);
    if (payload.hitT < 0.0) {
        payload = worldHit;
    }
}

public Payload makeShadowPayload() {
    Payload shadowPayload;
    shadowPayload.albedo = half3(1.0h, 1.0h, 1.0h);
    shadowPayload.hitT = -1.0; // water-crossing sentinel, filled by the water any-hit
    shadowPayload.normal = half3(0.0h, 0.0h, 0.0h);
    shadowPayload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    shadowPayload.f0 = half3(0.0h, 0.0h, 0.0h);
    // world_guide.rmiss clears this sentinel. An accepted opaque hit skips closest-hit and therefore
    // leaves it set, while ignored translucent/water hits continue until either a later opaque hit or miss.
    shadowPayload.flags = 1u;
    shadowPayload.roughMetal = 0u;
    shadowPayload.emissionSss = 0u;
    shadowPayload.iorTransmission = 0u;
    shadowPayload.rayCone = 0u;
    return shadowPayload;
}

public VisibilityResult visibility(float3 origin, float3 dir, float tmax) {
    // The payload still describes the surface currently being shaded: shadow traces use their own local
    // payload (see makeShadowPayload), so the caller's copy survives every occlusion query in this shading
    // pass. That is what lets one line here cover the sun NEE, RIS emitters, the held-item light's
    // occlusion, fog and the above-blocks shadow in both raygens: a viewmodel surface asks for the
    // player-free mask, everything else keeps the ordinary one.
    return visibilityCulled(origin, dir, tmax,
            (payload.flags & PAYLOAD_VIEWMODEL) != 0u ? CULL_VIEWMODEL_SHADOW : CULL_SECONDARY);
}

// Explicit instance-cull variant of visibility(); the held-item light uses CULL_HAND_LIGHT so the
// first-person viewmodel cannot occlude the very light it carries (see the constant's comment).
public VisibilityResult visibilityCulled(float3 origin, float3 dir, float tmax, uint cullMask) {
    // Vulkan requires an identical payload structure for every stage reachable by this trace. The shadow
    // path uses only albedo as accumulated transmittance and hitT as the nearest-water crossing.
    Payload shadowPayload = makeShadowPayload();
    // Shadow SBT records run any-hit only for cutout/translucent/water. Cutout alpha-tests; translucent
    // and water tint shadowPayload.albedo and pass through. Solid blocks terminate traversal. There is no
    // closest shader worth executing. The lightweight guide miss clears the flags sentinel so this path
    // needs only ordinary TraceRay and no invocation-reorder capability.
    TraceRay(topLevelAS,
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            cullMask, SBT_SHADOW, SBT_STRIDE_BUCKET, MISS_GUIDE,
            makeRay(origin, RAY_TMIN, dir, tmax), shadowPayload);
    VisibilityResult result;
    result.transmittance = shadowPayload.flags == 0u
            ? shadowPayload.albedo : float3(0.0, 0.0, 0.0);
    result.waterHitT = shadowPayload.hitT;
    return result;
}

// `valid` is false when the point projects behind the previous camera, where the perspective divide
// would otherwise manufacture an arbitrarily large NDC. Mirror images reach that case easily: a virtual
// image sits as far behind the reflector as the source is in front, so a reflection seen at a grazing
