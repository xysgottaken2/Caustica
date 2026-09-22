// Experimental SHaRC (Spatially Hashed Radiance Cache) integration for Caustica.
//
// This is Caustica's shader-side implementation of NVIDIA's Spatial Hash Radiance Cache idea
// (https://github.com/NVIDIA-RTX/SHARC), adapted to Caustica's single-pass wavefront tracer.
// The host allocates one persistent storage buffer and publishes its device address in WorldPush;
// this module owns the hash, the temporal blend, the query/update helpers and the per-path
// back-propagation state.
//
// The contract follows NVIDIA's three-pass design (Update / Resolve / Render), collapsed into the
// single render pass Caustica already runs:
//
//   * WHAT AN ENTRY STORES: the temporally accumulated OUTGOING radiance of the surfaces inside one
//     world-space cell (emission + direct NEE/RIS + held-item light + thin-surface SSS at those
//     surfaces, PLUS the light back-propagated from deeper path vertices and from sky misses). That
//     is exactly the quantity NVIDIA's resolved buffer holds: the light a path arriving at that
//     cell would take away from it.
//   * UPDATE (every shaded diffuse-capable vertex): demodulate the local light the path just added
//     by the path throughput (that recovers the vertex's own outgoing radiance), average it into
//     the entry with a count-weighted blend, AND back-propagate it into the previous vertices'
//     entries weighted by the throughput ratio between the vertices. The back-propagation is what
//     turns the cache from a "direct light field" into a real multi-bounce GI cache.
//   * RENDER/QUERY (bounce >= startBounce): BEFORE the vertex adds any local light, query the cache
//     at the vertex's own cell. On a hit the cached outgoing radiance REPLACES the vertex's entire
//     shading and the rest of the path: L += throughput * cached, terminate (this is exactly the
//     RTXGI SDK sample's `sampleRadiance += sharcRadiance * throughput; break;`). No albedo, no
//     1/pi, no confidence factor - the cached value is already outgoing radiance.
//
// Correctness guards mirrored from NVIDIA's implementation and integration guide:
//   * the query requires the arriving segment to be longer than one cell diagonal, so a path can
//     never read back an entry its own previous vertex wrote (self-feedback);
//   * back-propagation skips vertex pairs closer than one cell diagonal for the same reason;
//   * the normal's sign octant is folded into the hash key (NVIDIA's HASH_GRID_USE_NORMALS), so a
//     floor and a wall inside the same voxel land in different slots instead of fighting over one;
//   * the cell hash is computed from INT coordinates: Minecraft world space is negative in half
//     the world (x/z west/north, y below 0), and a float->uint cast of a negative floor is
//     undefined and collapses those cells onto 0;
//   * collisions, stale entries, normal mismatch and cleared data are all treated as "no cache
//     data", so a bad entry can never turn a pixel black.
//
// The cache is a single flat spatial hash: cell = floor(pos / cellSize) as int3,
// index = hash(cell, normalOctant) % entryCount. Temporal accumulation is a sample-count-weighted
// running mean whose effective sample count decays once per frame (the first write of a frame),
// which reproduces NVIDIA's frame-bounded accumulation window without a separate resolve pass.

import world_common;

// 48-byte entry (all float4/uint4 aligned, no tail padding) — must match RtSharc.ENTRY_BYTES:
//   radiance.xyz  = temporally accumulated mean outgoing radiance of the cell's surfaces
//   radiance.w    = effective sample count (0 = empty/invalid; decays once per frame)
//   cellFrame.xyz = quantised integer cell coordinate stored as int bit patterns (negatives safe)
//   cellFrame.w   = frame stamp of the last write (for staleness eviction)
//   normal.xyz    = accumulated mean surface normal (directional gate for queries)
//   normal.w      = unused
public struct SharcEntry {
    public float4 radiance;
    public uint4   cellFrame;
    public float4  normal;
};

// Debug overlay id (RtVideoOptions' debug view list). Pass A skips this id so pass B — the pass
// that actually queries the cache — owns the output; see world.rgen's main().
public static const uint SHARC_DEBUG_VIEW_ID = 13u;

// Per-pixel SHaRC debug state, aggregated across SPP samples and split leaves by max/any.
public static uint gv_sharcDbgQueried = 0u; // any cache query was attempted
public static uint gv_sharcDbgHit = 0u;     // any query succeeded
public static float gv_sharcDbgCount = 0.0; // sample count of the best hit (warmth of the entry)

// Path vertices remembered for back-propagation. NVIDIA tracks SHARC_PROPAGATION_DEPTH vertices;
// two is their default with cache resampling and keeps the inline write cost bounded.
public static const uint SHARC_PROPAGATION_DEPTH = 2u;
public static const uint SHARC_INVALID_INDEX = 0xFFFFFFFFu;

// Sentinel distance guard: queries and back-propagation only run between positions further apart
// than one cell diagonal, which guarantees they cannot touch the previous vertex's own entry.
public static const float SHARC_CELL_DIAGONAL = 1.7320508;

public struct SharcPathState {
    public uint v0Index;         // cache slot of the most recent registered vertex
    public float3 v0Pos;         // its terrain-rebased position (distance guards only; hashing converts)
    public float3 v0Throughput;  // path throughput when it was registered
    public uint v1Index;         // second-most-recent registered vertex
    public float3 v1Pos;
    public float3 v1Throughput;
};

public SharcPathState sharcInitPath() {
    SharcPathState s;
    s.v0Index = SHARC_INVALID_INDEX;
    s.v0Pos = float3(0.0, 0.0, 0.0);
    s.v0Throughput = float3(1.0, 1.0, 1.0);
    s.v1Index = SHARC_INVALID_INDEX;
    s.v1Pos = float3(0.0, 0.0, 0.0);
    s.v1Throughput = float3(1.0, 1.0, 1.0);
    return s;
}

// The cache is only usable when the host has published a real buffer and sane parameters.
public bool sharcReady(WorldPush push) {
    if (push.sharcCacheAddr == 0) {
        return false;
    }
    uint count = uint(push.sharcGridOrigin.w);
    if (count == 0u || push.sharcParams.x <= 0.0 || push.sharcParams2.z <= 0.0) {
        return false;
    }
    return true;
}

// Integer spatial hash (xorshift-multiply over cell coordinate + normal octant).
public uint sharcHash(uint3 cell, uint normalBits) {
    uint h = cell.x * 0x9E3779B1u;
    h ^= cell.y * 0x85EBCA77u;
    h ^= cell.z * 0xC2B2AE3Du;
    h ^= normalBits * 0x27D4EB2Fu;
    h ^= h >> 16u;
    h *= 0x7FEB352Du;
    h ^= h >> 15u;
    h *= 0x846CA68Bu;
    h ^= h >> 16u;
    return h;
}

// World-space position of a terrain-rebased hit. Caustica's tracer positions (ro + rd * t) live in
// the terrain origin's rebased coordinates, NOT in camera-relative space — but subtracting camOffset
// produces exactly the camera-relative position (see gv_hitCamRel in guides.slang), which moves with
// the player every frame and even flips between cells with the per-frame sub-pixel TAA jitter. Adding
// the terrain origin (published in sharcGridOrigin.xyz, the same anchor the water wave domain and NRD
// use) yields absolute world coordinates, stable across camera movement AND terrain rebases. Every
// public function in this module takes a terrain-rebased position and converts through here.
public float3 sharcWorldPos(WorldPush push, float3 hitPos) {
    return hitPos + float3(push.sharcGridOrigin.xyz);
}

// Cell coordinates as int bit patterns. float->uint of a negative floor is undefined (and collapses
// to 0 on real hardware), so the floor result goes through int3 first: int->uint wraps modulo 2^32,
// which keeps every negative cell distinct and comparable by equality.
public uint3 sharcCell(WorldPush push, float3 hitPos) {
    float cellSize = push.sharcParams.x;
    float3 p = sharcWorldPos(push, hitPos);
    int3 cell = int3(int(floor(p.x / cellSize)),
                     int(floor(p.y / cellSize)),
                     int(floor(p.z / cellSize)));
    return uint3(cell);
}

// NVIDIA's HASH_GRID_USE_NORMALS: fold the normal's sign octant into the hash key so differently
// oriented surfaces inside one voxel occupy different slots. The small bias keeps surfaces nearly
// parallel to a coordinate plane from flickering between octants.
public uint sharcNormalBits(float3 n) {
    return uint(n.x + 1.0e-3 >= 0.0 ? 0u : 1u)
         + uint(n.y + 1.0e-3 >= 0.0 ? 0u : 2u)
         + uint(n.z + 1.0e-3 >= 0.0 ? 0u : 4u);
}

public uint sharcIndex(WorldPush push, uint3 cell, uint normalBits) {
    return sharcHash(cell, normalBits) % uint(push.sharcGridOrigin.w);
}

// Per-frame decay of the effective sample count: the temporal blend knob is the inverse
// accumulation window in frames (NVIDIA's accumulationFrameNum). blend = 0.125 -> an 8-frame
// window; blend -> 0 approaches NVIDIA's 1024-frame maximum.
public float sharcDecay(WorldPush push) {
    float knob = clamp(push.sharcParams.z, 1.0 / 1024.0, 0.99);
    float window = clamp(round(1.0 / knob), 2.0, 1024.0);
    return (window - 1.0) / window;
}

public uint sharcFrameStamp(WorldPush push) {
    // Stamp 0 is reserved for "never written"; frame indices start wherever the host counter
    // starts, so clamp to at least 1.
    return max(push.frameIndex, 1u);
}

// Looks up the cached OUTGOING radiance for `pos`/`n`. Returns false for any stale, colliding,
// unpopulated or wrongly-oriented entry — callers must treat that as "shade normally".
// On success `radiance` is the mean outgoing radiance of the cell's surfaces and `sampleCount` the
// entry's effective sample count (useful for debug warmth). The value must be combined by the
// caller as `throughput * radiance` — nothing else; it already contains the surface BRDF.
public bool sharcQuery(WorldPush push, float3 pos, float3 n,
                       out float3 radiance, out float sampleCount) {
    radiance = float3(0.0, 0.0, 0.0);
    sampleCount = 0.0;
    if (!sharcReady(push)) {
        return false;
    }
    uint3 cell = sharcCell(push, pos);
    uint index = sharcIndex(push, cell, sharcNormalBits(n));

    SharcEntry entry = ConstPtr<SharcEntry>(push.sharcCacheAddr)[index];
    if (entry.cellFrame.x != cell.x || entry.cellFrame.y != cell.y || entry.cellFrame.z != cell.z) {
        return false; // spatial-hash collision with a different cell
    }
    float samples = entry.radiance.w;
    if (samples <= 0.0 || entry.cellFrame.w == 0u) {
        return false; // empty
    }
    if (samples < push.sharcParams3.x) {
        return false; // not enough samples yet (was the old "stable frames" warm-up)
    }
    if (push.frameIndex - entry.cellFrame.w > uint(push.sharcParams2.z)) {
        return false; // stale
    }
    float storedLen = length(entry.normal.xyz);
    if (storedLen < 0.5) {
        return false; // never had a meaningful normal
    }
    // Directional gate: the entry describes surfaces of one orientation. This is a REJECT gate
    // only — deliberately NOT a radiance scale. Scaling the cached value by the normal agreement
    // silently zeroed every marginal hit (the "everything green but nothing changed" symptom).
    float nd = saturate(dot(entry.normal.xyz / storedLen, n));
    if (nd < push.sharcParams2.w) {
        return false;
    }
    radiance = entry.radiance.xyz;
    sampleCount = samples;
    return true;
}

// Update sparsity, mirroring NVIDIA's dedicated sparse Update pass: a fixed fraction of PATHS does
// the cache updating, and a selected path updates at EVERY vertex it shades. Per-path selection is
// what keeps each entry's samples complete — gating per vertex/cell instead would blend visits
// whose downstream light was never propagated, and the running mean would settle on a fraction of
// the true direct+indirect outgoing radiance.
public bool sharcPathSelected(WorldPush push, uint pathSeed) {
    float coverage = clamp(push.sharcParams2.y, 0.0, 1.0);
    if (coverage <= 0.001) {
        return false;
    }
    if (coverage >= 0.999) {
        return true;
    }
    uint h = pathSeed ^ (push.frameIndex * 2654435761u);
    h ^= h >> 16u;
    h *= 0x7FEB352Du;
    h ^= h >> 15u;
    return (h % 1000u) < uint(coverage * 1000.0);
}

// Warms the cache with a shaded vertex's own outgoing radiance (`radiance` = the local light the
// path added, already demodulated by the path throughput by the caller). Returns true and the slot
// index when the vertex was written AND registered for back-propagation; the caller then records
// position + throughput in its SharcPathState. Callers must only invoke this on paths selected by
// sharcPathSelected, so the vertex's downstream light reaches the entry through back-propagation.
public bool sharcWrite(WorldPush push, float3 pos, float3 n, float3 radiance,
                       out uint indexOut) {
    indexOut = SHARC_INVALID_INDEX;
    if (!sharcReady(push)) {
        return false;
    }
    // A single non-finite sample must never poison a cache entry: it would spread through the
    // temporal blend and then through every query that hits the cell.
    if (any(isnan(radiance)) || any(isinf(radiance))) {
        return false;
    }
    uint3 cell = sharcCell(push, pos);
    uint normalBits = sharcNormalBits(n);
    uint index = sharcIndex(push, cell, normalBits);

    DevicePtr<SharcEntry> cache = DevicePtr<SharcEntry>(push.sharcCacheAddr);
    SharcEntry entry = cache[index];
    bool sameCell = entry.cellFrame.x == cell.x
            && entry.cellFrame.y == cell.y
            && entry.cellFrame.z == cell.z;
    bool occupied = sameCell && entry.radiance.w > 0.0 && entry.cellFrame.w != 0u;
    bool stale = entry.cellFrame.w == 0u
            || (push.frameIndex - entry.cellFrame.w > uint(push.sharcParams2.z));
    // A different live cell owns this hash slot: leave it alone (an aggressive overwrite would make
    // the cache churn between near misses and destroy temporal reuse).
    if (!sameCell && !stale && entry.radiance.w > 0.0) {
        return false;
    }

    uint frame = sharcFrameStamp(push);
    float3 clamped = clamp(radiance, float3(0.0, 0.0, 0.0), float3(65504.0, 65504.0, 65504.0));
    if (!occupied) {
        // Fresh (or evicted) entry: take the slot with the first sample as-is.
        SharcEntry fresh;
        fresh.radiance = float4(clamped, 1.0);
        fresh.cellFrame = uint4(cell.x, cell.y, cell.z, frame);
        fresh.normal = float4(normalize(n + float3(1.0e-6, 1.0e-6, 1.0e-6)), 0.0);
        cache[index] = fresh;
    } else {
        float samples = max(entry.radiance.w, 0.0);
        if (entry.cellFrame.w != frame) {
            samples *= sharcDecay(push);
        }
        float blend = 1.0 / (samples + 1.0);
        SharcEntry updated = entry;
        updated.radiance = float4(entry.radiance.xyz * (1.0 - blend) + clamped * blend,
                                  min(samples + 1.0, 65000.0));
        updated.cellFrame = uint4(cell.x, cell.y, cell.z, frame);
        // Keep the mean normal tracking the contributing surfaces (they share the octant by key).
        // A pathological hash collision between opposite orientations can drive the lerp toward
        // zero; normalizing that would produce NaN, so fall back to this sample's normal.
        float3 blended = lerp(normalize(entry.normal.xyz + float3(1.0e-6, 1.0e-6, 1.0e-6)), n, blend);
        float3 meanNormal = length(blended) > 1.0e-4 ? normalize(blended) : n;
        updated.normal = float4(meanNormal, 0.0);
        cache[index] = updated;
    }
    indexOut = index;
    return true;
}

// Back-propagation write: adds light observed further down the path into a previously registered
// vertex's entry. `value` must already be scaled by the transport factor between the two vertices
// (throughput_now / throughput_at_registration). The entry is not re-keyed or re-gated — it was
// validated when the vertex registered, and a live entry cannot be stolen mid-frame.
//
// A propagated contribution is part of the SAME visit's outgoing radiance as the vertex's own
// direct light, not a new observation: it adds to the mean at the weight this visit's own write
// used (1/samples, since the count already includes it) WITHOUT advancing the sample count.
// Counting it as a fresh sample would average the direct and propagated contributions against
// each other instead of summing them, systematically darkening the cache — NVIDIA's accumulator
// adds propagated radiance to the numerator while only own writes advance the sample count
// (SharcAddVoxelData's sampleData is 0 on the propagation path unless responsive lighting is on).
public void sharcPropagate(WorldPush push, uint index, float3 value) {
    if (!sharcReady(push) || index == SHARC_INVALID_INDEX) {
        return;
    }
    if (any(isnan(value)) || any(isinf(value))) {
        return;
    }
    DevicePtr<SharcEntry> cache = DevicePtr<SharcEntry>(push.sharcCacheAddr);
    SharcEntry entry = cache[index];
    if (entry.radiance.w <= 0.0 || entry.cellFrame.w == 0u) {
        return; // entry was cleared or lost: nothing sensible to blend into
    }
    float samples = max(entry.radiance.w, 0.0);
    float3 clamped = clamp(value, float3(0.0, 0.0, 0.0), float3(65504.0, 65504.0, 65504.0));
    // 1/samples mirrors the weight this visit's own write blended in at (it got 1/(samples), since
    // the count was samples-1 before it); the entry's count only advances on own writes, and a
    // registered entry always had one this frame.
    float scale = 1.0 / max(samples, 1.0);
    SharcEntry updated = entry;
    updated.radiance = float4(clamp(entry.radiance.xyz + clamped * scale,
            float3(0.0, 0.0, 0.0), float3(65504.0, 65504.0, 65504.0)), samples);
    updated.cellFrame.w = sharcFrameStamp(push);
    cache[index] = updated;
}

// Pushes `radianceValue` (the outgoing radiance leaving `pos`, e.g. a shaded vertex's local light,
// a sky miss, or a cache hit used as a terminal estimate) back into every registered path vertex.
// NVIDIA's SharcUpdateHit/SharcUpdateMiss back-propagation: each earlier voxel learns the light
// that would arrive at it through the continuation, which is what makes the cache multi-bounce.
public void sharcPropagateToPath(WorldPush push, SharcPathState state,
                                  float3 pos, float3 radianceValue, float3 throughput) {
    if (!sharcReady(push)) {
        return;
    }
    if (any(isnan(radianceValue)) || any(isinf(radianceValue))) {
        return;
    }
    float minDist = push.sharcParams.x * SHARC_CELL_DIAGONAL;
    if (state.v0Index != SHARC_INVALID_INDEX
            && max(state.v0Throughput.r, max(state.v0Throughput.g, state.v0Throughput.b)) > 1.0e-4
            && length(pos - state.v0Pos) > minDist) {
        // Transport factor from the registered vertex to now. Clamped per channel: legitimate
        // chains stay well below this, while a near-zero throughput channel would otherwise turn
        // the ratio into an unusable spike.
        float3 weight = clamp(throughput / state.v0Throughput, float3(0.0, 0.0, 0.0), float3(16.0, 16.0, 16.0));
        sharcPropagate(push, state.v0Index, radianceValue * weight);
    }
    if (state.v1Index != SHARC_INVALID_INDEX
            && max(state.v1Throughput.r, max(state.v1Throughput.g, state.v1Throughput.b)) > 1.0e-4
            && length(pos - state.v1Pos) > minDist) {
        float3 weight = clamp(throughput / state.v1Throughput, float3(0.0, 0.0, 0.0), float3(16.0, 16.0, 16.0));
        sharcPropagate(push, state.v1Index, radianceValue * weight);
    }
}

// Registers a freshly written vertex as a back-propagation target (most recent first).
public void sharcRegisterPathVertex(inout SharcPathState state, uint index,
                                    float3 pos, float3 throughput) {
    state.v1Index = state.v0Index;
    state.v1Pos = state.v0Pos;
    state.v1Throughput = state.v0Throughput;
    state.v0Index = index;
    state.v0Pos = pos;
    state.v0Throughput = throughput;
}
