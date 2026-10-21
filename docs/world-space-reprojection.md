# World-space reprojection (motion vectors)

Every temporal consumer in the renderer — SVGF, NRD/REBLUR, DLSS-RR, the FSR 3 and XeSS
upscalers, and both frame generators — reprojects its history with the same `gMotion`
texture (render resolution, `rg16f`, `(previous - current)` in render pixels, jitter
subtracted from both sides).

## What changed

The vector used to be produced inside the guide pass (`shaders/world/guides.slang`,
`writeGuides`): the shaded point of the primary hit was pushed through
`worldPush.prevViewProj` and the difference to the pixel's own NDC became the motion
vector. It is now produced by a dedicated compute pass,
`shaders/world/world_reproject.comp.slang`
(`RtWorldReprojectPipeline` in `src/main/java/.../rt/pipeline/`), from the depth
G-buffer:

```
jndc    = (pix + 0.5 + jitter) / size * 2 - 1        // the NDC the primary ray used
w       = gViewZ[pix]                                // linear view depth of the visible layer
camRel  = cameraRelativePointAtViewDepth(jndc, w)    // 3D point, camera-relative world space
prevPos = camRel + worldPush.camDelta + gReprojDelta  // same world point, previous camera frame
mv      = (project(prevPos, prevViewProj) - jndc) * 0.5 * size
```

`cameraRelativePointAtViewDepth` (`shaders/world/world_common.slang`) is the exact inverse
of the projection that produced `gViewZ`, so the reconstruction cannot disagree with the
tracer about the depth or handedness convention. The tracer's own
`math.slang::primaryRayDir` goes through the same helper.

The RT pipeline no longer has a motion-vector binding at all: guide slot 3 (binding 6)
now carries the reprojection correction described below, so `world_reproject.comp` is the
single writer by construction.

## Why the depth buffer

The old vector inherited the shading sample's whole error budget: the jittered hit,
per-vertex motion interpolation on entities and particles, the analytic water surface,
and the half-precision per-vertex displacement. A sub-pixel-accurate displacement field
is read by temporal filters as real surface motion and by frame generation as real
fractional-pixel travel between frames, so any noise in it is amplified rather than
averaged away.

Deriving the vector from `gViewZ` instead has three properties that matter:

* **Consistency with the validation guides.** SVGF's depth/normal gate, NRD's
  `IN_VIEWZ`, and the vector now all come from the same depth buffer, so the vector and
  the validation can no longer describe different surfaces.
* **A still camera produces exactly zero**, structurally: projecting any point that lies
  on the current pixel's ray with the current view-projection returns that pixel's NDC,
  so `prevPos == camRel` cancels regardless of the depth value or the jitter.
* **Cost.** The vector is now one depth read and two matrix multiplies per pixel, in a
  pass that does not run any rays.

## The correction guide (`gReprojDelta`)

A depth buffer only knows where a surface *is*, never where it was, and for transmitted
content it does not even describe the point the pixel shows. `writeGuides` therefore
publishes a per-pixel world-space correction (`rgba16f`, zero for the overwhelming
majority of the screen):

```
delta = gv_hitCamRel - gv_motionObjDisp - point-on-this-ray-at-guideViewZ
```

* **static terrain and sky**: `delta` is zero to within fp32 — the visible point lies on
  the pixel's ray, so the reprojection is pure depth reprojection;
* **dynamic geometry** (entities, particles, animated water): `delta` is the surface's
  own per-frame displacement, so its history follows the surface instead of standing
  still;
* **transmitted content** (water, glass, ice): the tuple describes the refracted
  *destination*, which is deliberately off the pixel's ray, so `delta` carries the
  lateral refraction offset and the reprojection lands where that destination really was.

Sky is special-cased to zero: `gViewZ` forces every sky pixel to the same 1e6 sentinel
while the guide point sits at `1e6 * cos(angle to the view axis)`. The difference is a
pure scale along one ray (identical screen position) and does not fit in fp16.

## Invariants (pinned by `RtWorldReprojectionTest`)

1. `guides.slang` must not write `gMotion`; `world_reproject.comp` is its only producer.
2. Both sides build the 3D point through `cameraRelativePointAtViewDepth` — no second
   inverse-projection implementation.
3. The vector is `(previous - current)` in render pixels, with `prevViewProj` (the
   *previous* frame's projection, carrying its FOV) and the frame's `WorldPushConstants`
   block — the same matrices and jitter the trace used.
4. `RtComposite` dispatches the reprojection after Pass A and before NRD/SVGF/DLSS-RR.

`gViewZ` must stay written unconditionally (it used to be gated on NRD-or-SVGF being
active); it is now the reprojection's depth source on every path.

## Debugging

Debug view **5 (Motion)** is painted by `world_reproject.comp`, because the final vector
does not exist until that pass runs. The fallback guide view shows `|gReprojDelta|` in
red: a nonzero value over static geometry is the shape of a reprojection bug.
