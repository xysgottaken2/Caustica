// Stateless math: luminance, GGX (D/G/VNDF), Fresnel, Henyey-Greenstein, the RR specular-albedo
// fit, the PCG stream, and direction sampling. No bindings, no globals, no rays.

import world_common;
import world_core;

public float luminance(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// GGX normal distribution.
// NOTE: the 1e-7 denominator guard is not merely a divide-by-zero epsilon, and it is load-bearing. At
// the lobe peak d == a2, so the guard dominates PI*d*d until a2 >> 1.8e-4, i.e. rough >> 0.116; every
// smoother material has its NEE highlight peak truncated. That truncation is what keeps the sun from
// being counted twice: a specular continuation sets showCelestial, so the BSDF-sampled ray may hit the
// sun disc in world.rmiss while NEE is also adding a sun term for the same lobe, with no MIS weighting
// between them. Removing the guard restores the physical peak AND the double count, and makes the
// single-sample sun estimator explode wherever the lobe is tighter than the disc (~0.0047 rad). The
// real fix is MIS (or a disc-aware specular light sample), not an epsilon edit — left as-is.
public float ggxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-7);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
public float ggxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

public float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
public static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
public static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)
// Keep SSS on the first two Pass B hits. Pass A's primary/interface prefix is outside this local depth.
public static const int   MAX_SSS_INDIRECT_DEPTH = 1;
public float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (== the linear roughness materials store), NoV is the first-hit view cosine.
public float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
public float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG hash RNG.
public uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
public float rndf(inout uint s) {
    // Convert the high 24 bits, which are exactly representable as float. Converting all 32 bits first
    // lets the top 128 uint values round to 2^32, incorrectly returning 1.0 and breaking `< probability`
    // tests (most seriously the F == 1 total-internal-reflection branch).
    return float(pcg(s) >> 8u) * (1.0 / 16777216.0);
}

// ---- Blue-noise dither (RtBlueNoise / WorldPush.blueNoiseAddr) -------------------------------
//
// The first stochastic choice at every shading vertex (one BSDF direction pair, one NEE light-position
// pair) is drawn from a 64x64 blue-noise tile instead of the white-noise PCG stream. Blue noise keeps
// the same marginal distribution — every channel is an exactly uniform permutation of 0..255 — but
// puts its energy at high spatial frequencies, so neighbouring pixels receive well-spread samples and
// the residual error reads as fine dither a denoiser removes easily, rather than clumpy grain. The
// tile is rotated (toroidally shifted) by frame and sample so a static scene still averages the full
// sequence over time; that shift is what keeps the estimate unbiased.
//
// The remaining decisions (lobe selection, Russian roulette, dielectric branch) deliberately stay on
// PCG: they are independent draws and reusing a blue-noise component already spent on a direction
// would correlate two decisions that must be independent.
public static const int BLUE_NOISE_TILE = 64;
public static const int BLUE_NOISE_MASK = BLUE_NOISE_TILE - 1;

// One RGBA8 tile texel at (pixel + per-frame/per-sample shift), decoded to 0..1. Returns zero (and
// reads nothing) when the host published no tile, so callers gate on blueNoiseEnabled() only to
// choose between the tile value and a PCG draw.
public float4 blueNoiseTexel(uint2 pixel, uint salt) {
    if (worldPush.blueNoiseAddr == 0) {
        return float4(0.0, 0.0, 0.0, 0.0);
    }
    // Odd strides decorrelate adjacent samples/bounces; frameIndex scrolls the whole tile so the
    // dither pattern is not static on screen.
    uint2 shifted = pixel + uint2(
            worldPush.frameIndex * 11u + salt * 7u,
            worldPush.frameIndex * 7u + salt * 13u);
    uint tileX = shifted.x & uint(BLUE_NOISE_MASK);
    uint tileY = shifted.y & uint(BLUE_NOISE_MASK);
    uint word = ConstPtr<uint>(worldPush.blueNoiseAddr)[tileY * uint(BLUE_NOISE_TILE) + tileX];
    // Byte levels are exactly uniform over 0..255; the +0.5 maps them to the MIDPOINTS of 256 equal
    // bins, so the result is uniform over (0, 1) and can never hand a `< probability` test exactly 1.0
    // (the same failure rndf's 24-bit conversion avoids).
    float4 levels = float4(float(word & 255u), float((word >> 8u) & 255u),
            float((word >> 16u) & 255u), float(word >> 24u));
    return (levels + 0.5) * (1.0 / 256.0);
}

// The unprojection itself lives in world_common (cameraRayDir) so the reprojection compute pass can
// share it without importing world_core's raygen bindings; this is the tracer-side spelling of the
// same ray, against the same matrix, so the two can never disagree about where a pixel points.
public float3 primaryRayDir(float2 ndc) {
    return cameraRayDir(ndc, worldPush.invViewProj);
}

public float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
// Explicit-pair form: u1/u2 are uniform in [0, 1) — what the blue-noise call sites pass.
public float3 cosineDir(float3 n, float u1, float u2) {
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// PCG-stream form: two draws from the caller's stream, identical to the pre-blue-noise behaviour.
public float3 cosineDir(float3 n, inout uint s) {
    return cosineDir(n, rndf(s), rndf(s));
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's
// sun/moon are square quads, so the NEE shadow ray samples a square (not a cone) — the same square the
// visible disc in world.rmiss uses, built from the same celestial tangent frame (right = arc-travel
// direction, from worldPush.celestial.xyz). Averaged over frames by DLSS-RR this yields soft penumbrae that
// widen with occluder distance (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
// Explicit-pair form: u/v are two uniform values in [0, 1) (blue-noise components at the call sites
// that opt in), keeping the same uniform-square distribution over the light's angular extent.
public float3 sampleSquare(float3 axis, float halfAngle, float u, float v) {
    float3 right = normalize(cross(axis, worldPush.celestial.xyz));
    float3 up = cross(right, axis);
    float t = tan(halfAngle);
    float su = (u * 2.0 - 1.0) * t;
    float sv = (v * 2.0 - 1.0) * t;
    return normalize(axis + su * right + sv * up);
}

// PCG-stream form: two draws from the caller's stream, identical to the pre-blue-noise behaviour.
public float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    return sampleSquare(axis, halfAngle, rndf(s), rndf(s));
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. Paired with the separable Smith term, the
// importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
// Explicit-pair form (blue-noise callers): u1/u2 are uniform in [0, 1), same VNDF mapping.
public float3 sampleGGXVNDF(float3 n, float3 ve, float alpha, float u1, float u2) {
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float a = alpha;
    float3 Vh = normalize(float3(a * Ve.x, a * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(a * Nh.x, a * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                 // -> world space
}

// PCG-stream form: two draws from the caller's stream, identical to the pre-blue-noise behaviour.
public float3 sampleGGXVNDF(float3 n, float3 ve, float alpha, inout uint s) {
    return sampleGGXVNDF(n, ve, alpha, rndf(s), rndf(s));
}

public float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4, 2.4, 2.4));
    float3 isHi = step(float3(0.04045, 0.04045, 0.04045), c);
    return lerp(lo, hi, isHi);
}
