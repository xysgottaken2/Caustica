// CPU validation uses the ACTUAL production implementation, not a Python/Java noise reimplementation.
// Include (rather than import) intentionally exposes internal helpers to this test-only entry point.
#include "../../shaders/world/clouds.slang"

StructuredBuffer<float4> inputs;
RWStructuredBuffer<float4> outputs;
RWStructuredBuffer<uint> classicCells;

[shader("compute")]
[numthreads(1, 1, 1)]
void probe(uint3 id : SV_DispatchThreadID) {
    uint at = id.x * 7u;
    float4 pos = inputs[at];
    float4 cfg = inputs[at + 1u];
    float4 anchor = inputs[at + 2u];
    float4 ray = inputs[at + 3u];
    float4 light = inputs[at + 4u];
    float4 ambient = inputs[at + 5u];
    float4 flags = inputs[at + 6u];
    WorldPush push = {};
    push.clouds = float4(cfg.z, anchor.w, flags.y, anchor.z);
    push.cloudAnchor = float4(anchor.xy, cfg.y, 3072.0);
    push.cloudColor = float4(0.8, 0.85, 0.9, cfg.w);
    push.cloudThickness = float4(cfg.xy, 0.0, 0.0);
    push.lightDir = float4(light.xyz, 0.0);
    push.lightRadiance = float4(light.www, 0.0);
    push.dimension = uint(flags.z);
    push.featureFlags = FEATURE_CLOUDS | (flags.x == 0.0 ? FEATURE_CLOUDS_VOLUMETRIC : 0u);
    push.frameIndex = uint(flags.w);
    if (flags.x == 2.0) push.cloudCellsAddr = uint64_t(&classicCells[0]);
    float2 samplePos = push.cloudAnchor.xy + pos.xz;
    if (pos.w == 0.0) {
        outputs[id.x] = float4(cloudVolumeDensity(push, pos.xyz),
                              cloudFormationThickness(push, samplePos),
                              cloudMacroCoverage(push, samplePos), cloudOpticalDensity(push, pos.xyz));
    } else if (pos.w == 1.0) {
        CloudVolume v = cloudSegment(push, pos.xyz, ray.xyz, ray.w, ambient.xyz, ambient.w > 0.0);
        outputs[id.x] = float4(v.scatter, v.transmittance);
    } else if (pos.w == 2.0) {
        outputs[id.x] = float4(cloudLightOpticalDepth(push, pos.xyz, light.xyz, int(ray.w)),
                              cloudSunShadow(push, pos.xyz, light.xyz),
                              cloudPhase(dot(ray.xyz, light.xyz)), 0.0);
    } else if (pos.w == 3.0) {
        outputs[id.x] = float4(cloudPerlin3(pos.xyz), cloudWorley3(pos.xyz),
                              cloudNoise(pos.xz), cloudCoverage(push, samplePos));
    } else {
        float t0, t1;
        bool hit = cloudSlabInterval(push, pos.xyz, ray.xyz, ray.w, t0, t1);
        outputs[id.x] = float4(hit ? 1.0 : 0.0, t0, t1, 0.0);
    }
}
