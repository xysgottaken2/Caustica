// World-space reprojection — the renderer's ONLY producer of screen-space motion vectors.
//
// Every temporal consumer (SVGF, NRD/REBLUR, DLSS-RR, the FSR 3 / XeSS upscalers and both frame
// generators) reads `gMotion`. It used to be written by the guide pass in world_primary.rgen, from
// the shaded point's own 2D reprojection. This pass replaces that producer wholesale; the buffer,
// its format, its units and every consumer are unchanged.
//
// `gSpecMotion` is deliberately NOT covered: it is DLSS-RR's reflection vector, which reprojects the
// MIRROR IMAGE of the reflected content (guides.slang's previousReflectionNdc). A depth buffer holds
// the visible layer only, so a mirror's virtual image has no depth to reconstruct; that vector stays
// analytic.
//
// Why replace it
// --------------
// A screen-space motion vector exists to answer one question: "where was the surface visible at this
// pixel, one frame ago?" Deriving it by pushing a 1-spp ray-traced point through prevViewProj makes
// the answer inherit that point's noise. Sub-pixel scatter from the jittered sample, per-vertex
// motion interpolation on entities and particles, the analytic wave surface and the half-precision
// per-vertex displacement all land in a texture whose entire job is to be a smooth, sub-pixel-accurate
// displacement field. A few hundredths of a pixel of error per frame is invisible in a single pixel
// and obvious across a still screen: the history fetch lands in a different bilinear neighbourhood
// every frame, and lights, reflections and sharp edges crawl. Standing still is the worst case — the
// camera contributes nothing, so that error is the whole vector.
//
// How it works
// ------------
// Reconstruct the pixel's exact 3D position from THIS frame's depth G-buffer, then project that same
// world point through the PREVIOUS frame's view-projection:
//
//   1. jndc    = the NDC the primary ray was actually cast through (pixel centre + this frame's
//                jitter, exactly as world_primary.rgen builds it);
//   2. w       = viewZ[pix], the linear view depth of the visible layer (the projection's clip .w);
//   3. camRel  = the point on that ray at depth w, in camera-relative world space
//                (world_common.cameraRelativePointAtViewDepth);
//   4. prevPos = camRel + camDelta + reprojDelta  — camDelta moves the point into the PREVIOUS
//                camera's frame, reprojDelta carries the one thing depth cannot know (see below);
//   5. mv      = (project(prevPos, prevViewProj) - jndc) * 0.5 * size, in render pixels.
//
// For static geometry this is exact by construction: the depth buffer holds the surface, projecting
// it with the previous camera lands on the pixel it occupied last frame, and a still camera yields
// exactly zero — no per-sample scatter to amplify, because the position is derived from the depth
// buffer rather than from the shading sample. It is also the same geometry the denoisers validate
// the reprojected history against (gViewZ / gNormal / their previous-frame copies), so the vector and
// the validation can no longer disagree about where a surface was.
//
// The guide (`gReprojDelta`)
// --------------------------
// A depth-only scheme cannot express two things, and both are real in this renderer:
//
//   * dynamic geometry — the depth buffer says where an entity IS, never where it was, so a moving
//     entity would reproject as if it were static and smear;
//   * transmitted content — for water, glass and ice the guide tuple describes the refracted
//     DESTINATION, which is deliberately off the pixel's own ray, so reconstructing along the ray at
//     its depth lands on a different point than the one the pixel shows.
//
// The guide pass therefore publishes the world-space correction between "the point depth describes"
// and "the point the pixel shows", already minus the surface's own per-frame displacement. It is
// zero to within fp32 for static terrain and sky, the per-frame displacement for dynamic surfaces,
// and the lateral refraction offset for transmitted ones — a few blocks at most, which is why fp16
// is enough. This pass then reprojects a 3D point in every case; the correction never becomes an
// independent 2D vector.
//
// Units and sign are what the consumers already expect: (previous - current) in RENDER pixels,
// jitter subtracted from both sides (so a static surface is exactly zero, matching the MVJittered
// flag being unset), y in the same direction as the texture rows.

import world_common;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(0, 0)]] [format("r32f")]    public RWTexture2D<float>  viewZ;       // this frame's linear view depth (G-buffer)
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> reprojDelta; // per-pixel world-space correction
[[vk::binding(2, 0)]] [format("rg16f")]   public RWTexture2D<float2> motion;      // OUT: previous - current, render px
[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;    // debug view 5 ("Motion")

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];

    uint width, height;
    viewZ.GetDimensions(width, height);
    int2 pix = int2(tid.xy);
    if (pix.x >= int(width) || pix.y >= int(height)) {
        return;
    }
    float2 size = float2(width, height);

    // The tracer's primary ray for this pixel was cast through this exact NDC (see
    // world_primary.rgen's main): pixel centre plus the frame's jitter, in render pixels.
    float2 jndc = ((float2(pix) + 0.5) + worldPush.jitter) / size * 2.0 - 1.0;

    float depth = viewZ[pix];
    float2 mv = float2(0.0, 0.0);
    // A non-positive or non-finite depth has no surface (uninitialised buffer, a pixel behind the
    // eye, or a degenerate projection). Report no motion rather than a teleport: every consumer
    // treats a zero vector as "history unavailable here".
    if (depth > 0.0 && !isinf(depth) && !isnan(depth)) {
        float3 camRel = cameraRelativePointAtViewDepth(jndc, depth, worldPush.invViewProj);

        float4 guide = reprojDelta[pix];
        float3 delta = guide.xyz;
        if (any(isnan(delta)) || any(isinf(delta))) {
            delta = float3(0.0, 0.0, 0.0);
        }

        // camDelta is (current camera - previous camera), so adding it expresses the same world point
        // in the PREVIOUS camera's frame; prevViewProj then projects it the way the previous frame saw
        // it. Terrain rebases are invisible here: both sides are camera-relative.
        float3 prevCamRel = camRel + worldPush.camDelta + delta;
        float4 prevClip = mul(worldPush.prevViewProj, float4(prevCamRel, 1.0));
        // w <= 0 means the point was behind the previous camera (or on its plane): no valid previous
        // position exists, so report no motion and let the consumer fall back.
        if (prevClip.w > 0.0) {
            mv = (prevClip.xy / prevClip.w - jndc) * 0.5 * size;
            if (any(isnan(mv)) || any(isinf(mv))) {
                mv = float2(0.0, 0.0);
            }
        }
    }
    motion[pix] = mv;

    // Debug view 5 ("Motion"). This pass owns it because it is the only place the final, current-frame
    // vector exists; the guide pass runs before it and would only be able to show the previous frame.
    if (pc.debugView == 5u) {
        outImage[pix] = float4(clamp(0.5 + mv * 0.05, 0.0, 1.0), 0.5, 1.0);
    }
}
