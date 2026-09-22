// PathSegment — a resumable continuation — plus its packed 48-byte buffer form. This is the record
// the primary pass writes and the indirect pass reads. Depends on medium.


// Everything needed to resume tracing from a point in the scene. The path tracer takes one of these and
// may hand back another, which is how a dielectric's two Fresnel continuations are traced without
// instantiating the path tracer twice: the second branch is DATA, not a second inlined copy. `throughput`
// already carries the branch weight, so segments are simply summed.
//
// This is deliberately shaped like a record that could live in a buffer rather than a register: the
// planned split into a primary/guide pass and an indirect pass hands continuations between dispatches,
// and this is the payload it would write.

import world_common;
import world_core;
import medium;

public struct PathSegment {
    public float3 ro;
    public float3 rd;
    public float3 throughput;
    public MediumStack medium;
    public float rayConeWidth;
    public float rayConeSpread;
    public uint seed;
    public int bounce;          // interfaces already consumed, so RR start and the bounce cap stay global
    public bool showCelestial;
};

public PathSegment makePathSegment(float3 ro, float3 rd, float3 throughput, MediumStack medium,
                            float rayConeWidth, float rayConeSpread, uint seed,
                            int bounce, bool showCelestial) {
    PathSegment s;
    s.ro = ro;
    s.rd = rd;
    s.throughput = throughput;
    s.medium = medium;
    s.rayConeWidth = rayConeWidth;
    s.rayConeSpread = rayConeSpread;
    s.seed = seed;
    s.bounce = bounce;
    s.showCelestial = showCelestial;
    return s;
}
// field is a uint, so Std430DataLayout gives this an exact 48-byte stride.
public struct PackedPathSegment {
    public float3 ro;
    public uint rd;
    public uint throughput;
    public uint currentExtinction;
    public uint outerExtinction;
    public uint mediumIors;
    public uint rayCone;
    public uint seed;
    public uint pathFlags;
    public uint nextRecord;
};

public static const uint PATH_NO_NEXT = 0xffffffffu;

public float2 octEncode(float3 direction) {
    float3 n = direction / max(abs(direction.x) + abs(direction.y) + abs(direction.z), 1.0e-20);
    float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    float2 oct = n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * signNotZero;
    return oct * 0.5 + 0.5;
}

public float3 octDecode(float2 encoded) {
    float2 oct = encoded * 2.0 - 1.0;
    float3 n = float3(oct, 1.0 - abs(oct.x) - abs(oct.y));
    if (n.z < 0.0) {
        float2 signNotZero = float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(n.yx)) * signNotZero;
    }
    return normalize(n);
}

public uint packUnorm16x2(float2 v) {
    uint2 q = uint2(round(clamp(v, 0.0, 1.0) * 65535.0));
    return q.x | (q.y << 16u);
}

public float2 unpackUnorm16x2(uint p) {
    return float2(p & 0xffffu, p >> 16u) / 65535.0;
}

// DXGI_FORMAT_R9G9B9E5_SHAREDEXP, implemented locally because Slang exposes no packing intrinsic.
public uint packRgb9e5(float3 value) {
    float3 v = clamp(value, 0.0, 65408.0);
    float maxChannel = max(v.x, max(v.y, v.z));
    uint exponent = maxChannel < exp2(-16.0)
            ? 0u : uint(floor(log2(maxChannel))) + 16u;
    exponent = min(exponent, 31u);
    float scale = exp2(float(int(exponent) - 24));
    uint maxMantissa = uint(floor(maxChannel / scale + 0.5));
    if (maxMantissa == 512u && exponent < 31u) {
        exponent++;
        scale *= 2.0;
    }
    uint3 mantissa = min(uint3(floor(v / scale + 0.5)), uint3(511u, 511u, 511u));
    return mantissa.x | (mantissa.y << 9u) | (mantissa.z << 18u) | (exponent << 27u);
}

public float3 unpackRgb9e5(uint p) {
    float scale = exp2(float(int(p >> 27u) - 24));
    return float3(p & 0x1ffu, (p >> 9u) & 0x1ffu, (p >> 18u) & 0x1ffu) * scale;
}

public PackedPathSegment packPathSegment(PathSegment seg, uint nextRecord) {
    PackedPathSegment p;
    p.ro = seg.ro;
    p.rd = packUnorm16x2(octEncode(seg.rd));
    p.throughput = packRgb9e5(seg.throughput);
    p.currentExtinction = packRgb9e5(seg.medium.current.extinction);
    p.outerExtinction = packRgb9e5(seg.medium.outer.extinction);
    p.mediumIors = packHalf2(float2(seg.medium.current.ior, seg.medium.outer.ior));
    p.rayCone = packHalf2(float2(seg.rayConeWidth, seg.rayConeSpread));
    p.seed = seg.seed;
    p.pathFlags = (uint(seg.bounce) & 15u)
            | (seg.showCelestial ? 1u << 8u : 0u)
            | (seg.medium.current.water ? 1u << 9u : 0u)
            | (seg.medium.outer.water ? 1u << 10u : 0u);
    p.nextRecord = nextRecord;
    return p;
}

public PathSegment unpackPathSegment(PackedPathSegment p) {
    float2 iors = unpackHalf2(p.mediumIors);
    Medium current;
    current.ior = iors.x;
    current.extinction = unpackRgb9e5(p.currentExtinction);
    current.water = (p.pathFlags & (1u << 9u)) != 0u;
    Medium outer;
    outer.ior = iors.y;
    outer.extinction = unpackRgb9e5(p.outerExtinction);
    outer.water = (p.pathFlags & (1u << 10u)) != 0u;
    MediumStack medium;
    medium.current = current;
    medium.outer = outer;
    float2 cone = unpackHalf2(p.rayCone);
    return makePathSegment(p.ro, octDecode(unpackUnorm16x2(p.rd)),
            unpackRgb9e5(p.throughput), medium, cone.x, cone.y, p.seed,
            int(p.pathFlags & 15u), (p.pathFlags & (1u << 8u)) != 0u);
}

// Walk only the visually-primary dielectric chain. The terminal non-dielectric/miss trace is repeated
// by the indirect pass, so the returned record describes the state immediately BEFORE that trace.
// This keeps closest-hit payload data out of the queue and, critically, writes the record before any
