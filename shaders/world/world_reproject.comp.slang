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
// Reconstruct the pixel's exact 3D position from THIS frame's depth G-buffer, then project the point
// the pixel actually shows through both the CURRENT and the PREVIOUS view-projection:
//
//   1. jndc    = the NDC the primary ray was actually cast through (pixel centre + this frame's
//                jitter, exactly as world_primary.rgen builds it);
//   2. depth   = viewZ[pix], the linear view depth of the visible layer (the projection's clip .w);
//   3. depthP  = the point on that ray at that depth, camera-relative world space
//                (world_common.cameraRelativePointAtViewDepth);
//   4. shown   = depthP + lateral   — the 3D point the pixel really shows;
//   5. prev    = shown - ownMove + camDelta — the same point one frame earlier, in the previous
//                camera's frame;
//   6. mv      = project(prev, prevViewProj) - project(shown, curViewProj), in render pixels.
//
// BOTH ends are real world points and both are projected. That is what makes one formula correct for
// a static landscape, a walking mob, a falling raindrop and a fish seen through water:
//
//   * static geometry: lateral and ownMove are zero, so `shown` is on the pixel's ray at its depth,
//     proj_cur(shown) IS jndc, and the difference is exactly the depth reprojection (identically zero
//     for a still camera);
//   * dynamic geometry: ownMove moves the previous end along with the surface;
//   * precipitation: the sheets are static geometry whose fall lives in the sampled V coordinate, so
//     RtWeatherCapture hands them the texture scroll as their own motion (world blocks along y);
//   * transmitted content: `lateral` carries the refraction offset, so BOTH ends follow the visible
//     destination instead of assuming it sits on this pixel's ray. Subtracting jndc there (an earlier
//     version) added the destination's own screen offset as fake motion — the shaking water/glass.
//
// The guides (`gReprojDelta`, `gReprojDisp`)
// ------------------------------------------
// A depth-only scheme cannot express two things, and both are real in this renderer:
//
//   * dynamic geometry — the depth buffer says where an entity IS, never where it was;
//   * transmitted content — for water, glass and ice the guide tuple describes the refracted
//     DESTINATION, which is deliberately off the pixel's own ray.
//
// guides.slang publishes those as two separate WORLD-SPACE vectors (see world_core.slang). They stay
// world-space corrections; neither ever becomes an independent 2D vector, and every pixel — static,
// moving or refracted — is reprojected as a 3D point.
//
// Units and sign are what the consumers already expect: (previous - current) in RENDER pixels, with
// both ends projected from the same 3D point so the frame's sub-pixel jitter cancels between them
// rather than being double-counted. A static surface under a still camera is therefore exactly zero,
// which is what the consumers assume with the MVJittered flag unset. Y runs in the same direction as
// the texture rows.

import world_common;

[[vk::push_constant]] WorldPushConstants pc;

[[vk::binding(0, 0)]] [format("r32f")]    public RWTexture2D<float>  viewZ;       // this frame's linear view depth (G-buffer)
[[vk::binding(1, 0)]] [format("rgba32f")] public RWTexture2D<float4> reprojDelta; // lateral offset: shown point - depth point
[[vk::binding(2, 0)]] [format("rgba16f")] public RWTexture2D<float4> reprojDisp;  // the surface's own frame displacement
[[vk::binding(3, 0)]] [format("rg16f")]   public RWTexture2D<float2> motion;      // OUT: previous - current, render px
[[vk::binding(4, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;    // debug view 5 ("Motion")

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
        float3 depthPoint = cameraRelativePointAtViewDepth(jndc, depth, worldPush.invViewProj);

        float3 lateral = reprojDelta[pix].xyz;
        float3 ownMove = reprojDisp[pix].xyz;
        if (any(isnan(lateral)) || any(isinf(lateral))) {
            lateral = float3(0.0, 0.0, 0.0);
        }
        if (any(isnan(ownMove)) || any(isinf(ownMove))) {
            ownMove = float3(0.0, 0.0, 0.0);
        }

        // The point this pixel shows, and the same point one frame earlier.
        float3 shown = depthPoint + lateral;
        float3 prev = shown - ownMove + worldPush.camDelta;

        // Both ends are projected: the current one describes where the shown point is NOW (it is the
        // pixel centre for anything the ray hit, and the refracted destination's own screen position
        // otherwise), the previous one where it was. Only then does the difference mean "screen
        // displacement of the same surface point".
        float4 curClip = mul(worldPush.curViewProj, float4(shown, 1.0));
        float4 prevClip = mul(worldPush.prevViewProj, float4(prev, 1.0));
        // w <= 0 means the point is behind (or on the plane of) that camera: no valid projection
        // exists, so report no motion and let the consumer fall back instead of handing it garbage.
        if (curClip.w > 0.0 && prevClip.w > 0.0) {
            mv = (prevClip.xy / prevClip.w - curClip.xy / curClip.w) * 0.5 * size;
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
