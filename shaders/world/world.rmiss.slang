// Ray miss = sky - Final RTX vivid clean blue sky, no toggle
import world_common;
import clouds;

[[vk::push_constant]] WorldPushConstants pc;
// Vanilla celestials atlas (sun + moon-phase sprites), sampled for the discs below.
// IMPORTANT: this number tracks RtPipeline's skyBinding = firstExtraBinding(3) + GUIDE_COUNT —
// the guide images occupy bindings 3..(3+GUIDE_COUNT-1) and the atlas sampler sits right after
// them. It has been re-synced with GUIDE_COUNT more than once in this file's life (it used to be
// 9 back when GUIDE_COUNT was 6, and sampling the gViewZ storage image through the stale number
// turned the sun and moon black — "evaporated"). Do not trust any number parked here, including
// this one: RtCloudShaderRegressionTest.skyAtlasBindingFollowsGuideCount parses GUIDE_COUNT from
// RtComposite and the binding from this line every CI run, so the two sides failing to agree is
// a red build, not a sky without celestials.
[[vk::binding(12, 0)]] Sampler2D celestialsAtlas;

static const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

static const float3 SUN_DISC_RADIANCE  = float3(24.0, 23.0, 21.5);
static const float3 MOON_DISC_RADIANCE = float3(1.7, 1.85, 2.2) / 4.0;
static const float SUN_DISC_HALF_ANGLE  = 0.29146;
static const float MOON_DISC_HALF_ANGLE = 0.19740;
static const float3 NIGHT_ZENITH  = float3(0.004, 0.008, 0.022) / 5.0;
static const float3 NIGHT_HORIZON = float3(0.015, 0.022, 0.045) / 5.0;

// ---- AGGRESSIVE VIVID BLUE SKY (Minecraft RTX / Areal style) ----
// Zenith: pure saturated vibrant blue as requested 0.25,0.52,0.95
// Horizon: light cyan/blue bright 0.65,0.82,0.98 - no gray/brown haze
static const float3 SKY_ZENITH_VIVID  = float3(0.25, 0.52, 0.95);
static const float3 SKY_HORIZON_VIVID = float3(0.65, 0.82, 0.98);

static const float  PLANET_R   = 6371000.0;
static const float  ATMOS_R    = 6471000.0;
static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
static const float  OZONE_CENTER_H = 25000.0;
static const float  OZONE_WIDTH_H  = 15000.0;
static const float  SUN_INTENSITY = 24.0; // slightly brighter for clear day
static const int    ATMOS_PRIMARY_STEPS = 16;
static const int    ATMOS_LIGHT_STEPS   = 8;

// AGGRESSIVE: drastically cut Mie to eliminate gray haze, boost Rayleigh blue for vivid
static const float3 RAY_BETA   = float3(3.2e-6, 8.5e-6, 24.5e-6); // strong blue, minimal red
static const float  MIE_BETA   = 6.0e-6; // RADICALLY lowered from 38/24/18 -> 6 to kill gray/brown haze
static const float  RAY_SCALE_H = 9000.0;
static const float  MIE_SCALE_H = 900.0; // very thin Mie layer - only near horizon, not whole sky
static const float  MIE_G      = 0.70; // less forward, tighter sun glow, not broad whitish halo
static const float  SKY_SATURATION = 1.40; // vivid, saturated

float ozoneDensity(float h) { return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H); }

float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

float3 atmosphere(float3 dir, float3 sunDir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x);
    float segLen = (tEnd - tStart) / float(ATMOS_PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = 3.0 / (16.0 * PI) * (1.0 + mu * mu);
    float g = MIE_G;
    float phaseM = 3.0 / (8.0 * PI) * ((1.0 - g * g) * (1.0 + mu * mu))
                 / ((2.0 + g * g) * pow(max(1.0 + g * g - 2.0 * g * mu, 0.0), 1.5));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    float tCur = tStart;
    for (int i = 0; i < ATMOS_PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(ATMOS_LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < ATMOS_LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; }
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 tau = RAY_BETA * (odR + odRL) + MIE_BETA * 1.1 * (odM + odML)
                       + OZONE_BETA * (odO + odOL);
            float3 atten = exp(-tau);
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}

float3 transmittanceToSpace(float3 dir) {
    float3 orig = float3(0.0, PLANET_R + 2000.0, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.y <= 0.0) return float3(1.0, 1.0, 1.0);
    float segLen = t.y / float(ATMOS_LIGHT_STEPS);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < ATMOS_LIGHT_STEPS; i++) {
        float3 p = orig + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / RAY_SCALE_H) * segLen;
        odM += exp(-h / MIE_SCALE_H) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return exp(-(RAY_BETA * odR + MIE_BETA * 1.1 * odM + OZONE_BETA * odO));
}

void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m);
}

float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

static const float STAR_CELLS     = 21.0;
static const float STAR_THRESHOLD = 0.86;
static const float STAR_HALF      = 0.045;
float3 stars(float3 dir, float starBrightness, float VdotS, float4 celestial) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return float3(0.0, 0.0, 0.0);
    float3 sdir = rotateAxis(dir, normalize(celestial.xyz), -celestial.w);
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return float3(0.0, 0.0, 0.0);
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5;
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y);
    float box = max(abs(q.x), abs(q.y));
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);
    if (star <= 0.0) return float3(0.0, 0.0, 0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return 0.2 * star * starCol * starBrightness;
}

static const float3 NETHER_SKY_LOW  = float3(0.36, 0.085, 0.030);
static const float3 NETHER_SKY_HIGH = float3(0.085, 0.024, 0.020);
static const float3 NETHER_SKY_HAZE = float3(0.052, 0.0125, 0.0065);
float3 netherSky(float3 dir) {
    float down = smoothstep(-0.75, 0.35, -dir.y);
    float3 col = lerp(NETHER_SKY_HIGH, NETHER_SKY_LOW, down);
    return lerp(col, col * 0.5 + NETHER_SKY_HAZE * 0.6, 0.5);
}

static const float3 END_SKY_DEEP  = float3(0.020, 0.010, 0.035);
static const float3 END_SKY_BAND  = float3(0.055, 0.028, 0.080);
static const float3 END_SKY_HAZE  = float3(0.010, 0.0055, 0.016);
static const float  END_STAR_BRIGHTNESS = 0.55;
float3 endSky(float3 dir) {
    float band = 1.0 - abs(dir.y);
    float3 col = lerp(END_SKY_DEEP, END_SKY_BAND, band * band);
    col = lerp(col, END_SKY_HAZE * 0.35, 0.35);
    float3 a = abs(dir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = dir.yz / a.x; face = dir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = dir.xz / a.y; face = dir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = dir.xy / a.z; face = dir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) >= STAR_THRESHOLD) {
        float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
        float inset = sz * 1.5;
        float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
        float2 q = frac(g) - center;
        float box = max(abs(q.x), abs(q.y));
        float star = 1.0 - smoothstep(sz * 0.6, sz, box);
        if (star > 0.0) {
            float3 starCol = lerp(float3(0.62, 0.55, 0.95), float3(0.90, 0.86, 1.0), hash13(id + 137.0));
            col += 0.2 * star * starCol * END_STAR_BRIGHTNESS * (0.55 + 0.45 * hash13(id + 71.0));
        }
    }
    return col;
}

void writeSkyMiss(inout Payload payload, float3 radiance) {
    payload.albedo = half3(clamp(radiance, float3(0.0, 0.0, 0.0),
            float3(HALF_MAX, HALF_MAX, HALF_MAX)));
    payload.hitT = -1.0;
    payload.normal = half3(0.0h, 0.0h, 0.0h);
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    payload.f0 = half3(0.0h, 0.0h, 0.0h);
    payload.flags = 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.iorTransmission = packHalf2(float2(1.0, 0.0));
    payload.rayCone = 0u;
}

[shader("miss")]
void main(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    float3 dir = normalize(WorldRayDirection());

    if (worldPush.dimension != DIMENSION_OVERWORLD) {
        writeSkyMiss(payload, worldPush.dimension == DIMENSION_NETHER
                ? netherSky(dir)
                : endSky(dir));
        return;
    }

    float day = worldPush.sunDir.w;
    float3 sd = worldPush.sunDir.xyz;
    float SdotU = sd.y;
    float VdotS = dot(dir, sd);
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0);
    float starBrightness = worldPush.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    float3 mdir = dir;
    float downFade = 1.0;
    if (dir.y < 0.0) {
        float2 flat2 = float2(dir.x, dir.z);
        float fl = length(flat2);
        mdir = fl > 1.0e-4 ? float3(flat2.x / fl, 0.0, flat2.y / fl) : float3(1.0, 0.0, 0.0);
        downFade = exp(dir.y * 3.0);
    }
    float3 col = atmosphere(mdir, sd);

    // RTX/Areal vivid: smooth gradient from light cyan at horizon to pure saturated blue at zenith
    // No gray/brown haze injection - clean blue only
    {
        float upFactor = saturate(dir.y);
        // Smoothstep for natural horizon -> zenith transition
        float t = pow(upFactor, 0.55);
        float3 vividGradient = lerp(SKY_HORIZON_VIVID, SKY_ZENITH_VIVID, t);
        float dayBlend = day * clamp(SdotU * 0.5 + 0.75, 0.0, 1.0);
        // Aggressive but clean: 0.18-0.28 blend to push vivid blue, not whitish
        float vividBlend = dayBlend * (0.18 + 0.12 * upFactor);
        col = lerp(col, vividGradient * 1.05, vividBlend);
    }

    // Luminance-preserving saturation boost for vivid clean look
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = max(lerp(float3(lum, lum, lum), col, SKY_SATURATION), float3(0.0, 0.0, 0.0));
    float up = clamp(dir.y, 0.0, 1.0);
    float3 nightAmbient = lerp(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col *= downFade;

    bool weatherOn = worldFeature(worldPush, FEATURE_WEATHER_LIGHTING);
    float rainStrength = weatherOn ? clamp(worldPush.weather.x, 0.0, 1.0) : 0.0;
    float skyDarken = weatherOn ? clamp(worldPush.weather.z, 0.0, 1.0) : 1.0;
    if (rainStrength > 0.0) {
        float overcastLum = dot(col, float3(0.2126, 0.7152, 0.0722));
        float3 overcast = float3(overcastLum, overcastLum, overcastLum) * float3(0.92, 0.95, 1.0);
        col = lerp(col, overcast, rainStrength * 0.85);
    }
    col *= skyDarken;
    col += stars(dir, starBrightness, VdotS, worldPush.celestial);

    float3 skyAmbient = col;

    // Sun/Moon discs - keep original logic, sun size unchanged as requested
    //
    // Rain hides the discs outright. It used to be `cloudsEnabled ? 1.0 : 1.0 - rainStrength` — i.e.
    // with clouds on the discs were left 100% visible on the bet that the deck would cover them. But
    // the deck is a shape (authored cells / noise), and any gap in it let the full sun punch straight
    // through onto the terrain during a downpour, exactly when the sky is supposed to be closed. The
    // discs now fade out with the rain lane itself, independently of the deck: the storm's overcast is
    // a STATE, not an occlusion lottery. Cloud gaps in lesser weather still hide/show the discs
    // geometrically via the cloudLayer composite below, as before.
    float discVisible = 1.0 - rainStrength;
    if (showCelestial && discVisible > 0.0) {
        float2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0))) {
            float2 uv = lerp(worldPush.sunUv.xy, worldPush.sunUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            float core = pow(clamp(dot(t, t) * 0.45, 0.0, 1.0), 6.0);
            col += SUN_DISC_RADIANCE * transmittanceToSpace(dir) * core * sunUp * discVisible;
        }
        float moonUp = clamp((worldPush.moonDir.y + 0.0625) / 0.125, 0.0, 1.0);
        squareBody(dir, worldPush.moonDir.xyz, MOON_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0)) && moonUp > 0.0) {
            float2 uv = lerp(worldPush.moonUv.xy, worldPush.moonUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            float m = smoothstep(0.0, 1.0, min(length(t), 1.0)) * 1.3;
            col += MOON_DISC_RADIANCE * transmittanceToSpace(dir) * t * m * pow2(1.0 - day) * moonUp * discVisible;
        }
    }

    col = cloudLayer(worldPush, WorldRayOrigin() - worldPush.camOffset, dir, 1.0e9, col, skyAmbient,
                     showCelestial);

    writeSkyMiss(payload, col);
}
