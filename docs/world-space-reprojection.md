# World-space reprojection (motion vectors)

Every temporal consumer in the renderer — SVGF, NRD/REBLUR, DLSS-RR, the FSR 3 and XeSS
upscalers, and both frame generators — reprojects its history with the same `gMotion`
texture (render resolution, `rg16f`, `(previous - current)` in render pixels, jitter
cancelled between the two ends).

## What changed

The vector used to be produced inside the guide pass (`shaders/world/guides.slang`,
`writeGuides`): the shaded point of the primary hit was pushed through
`worldPush.prevViewProj` and the difference to the pixel's own NDC became the motion
vector. It is now produced by a dedicated compute pass,
`shaders/world/world_reproject.comp.slang`
(`RtWorldReprojectPipeline` in `src/main/java/.../rt/pipeline/`), from the depth
G-buffer, with **both** of the vector's ends projected from a real 3D point:

```
jndc    = (pix + 0.5 + jitter) / size * 2 - 1        // the NDC the primary ray used
w       = gViewZ[pix]                                // linear view depth of the visible layer
depthP  = cameraRelativePointAtViewDepth(jndc, w)    // 3D point, camera-relative world space
shown   = depthP + gReprojDelta                      // the point this pixel really shows
prev    = shown - gReprojDisp + worldPush.camDelta   // the same point, previous camera frame
mv      = (project(prev, prevViewProj) - project(shown, curViewProj)) * 0.5 * size
```

`cameraRelativePointAtViewDepth` (`shaders/world/world_common.slang`) is the exact inverse
of the projection that produced `gViewZ`, so the reconstruction cannot disagree with the
tracer about the depth or handedness convention. The tracer's own
`math.slang::primaryRayDir` goes through the same helper.

The RT pipeline no longer has a motion-vector binding at all: guide slot 3 (binding 6)
now carries the reprojection's lateral offset and slot 9 (binding 12) its own-motion
vector — see below — so `world_reproject.comp` is the single writer by construction.

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
* **A still camera produces exactly zero**, structurally: for anything the ray actually
  hit, `project(shown, curViewProj)` *is* the pixel's NDC, so a static surface cancels
  exactly regardless of the depth value or the jitter.
* **Cost.** One depth read and two matrix multiplies per pixel, in a pass that runs no
  rays.

## The two guides

A depth buffer knows where a surface *is*, never where it was, and for transmitted
content it does not even describe the point the pixel shows. `writeGuides` therefore
publishes two independent **world-space** vectors, deliberately not one:

| guide | binding | format | meaning |
| --- | --- | --- | --- |
| `gReprojDelta` | 6 | `rgba32f` | `gv_hitCamRel - depthPoint` — how far the shown point is *laterally* from the point the depth buffer describes, at the same view depth |
| `gReprojDisp` | 12 | `rgba16f` | `gv_motionObjDisp` — the surface's own per-frame world displacement |

They are kept apart because the vector's two ends need different information:

* the **current** end needs the lateral offset (it moves the point onto the thing that is
  actually visible at this pixel);
* the **previous** end needs the own motion (it moves that same point back one frame).

Folding both into one vector — `gv_hitCamRel - gv_motionObjDisp - depthPoint`, which is
what the first version did — is exact for the previous end but leaves the current end
short by the lateral offset. For anything the ray hits that offset is ~0, which is why
the bug hid; for water and glass it is the whole refraction offset, so every such pixel
carried a large fake screen motion and the surfaces shook.

Per material:

* **static terrain and sky**: both guides are zero — pure depth reprojection;
* **dynamic geometry** (entities, particles, animated water): `delta ≈ 0`,
  `disp` = the per-frame displacement, so history follows the surface;
* **precipitation**: the sheets are static geometry whose fall lives entirely in the
  sampled V coordinate, so the capture hands them the texture scroll as their world-space
  displacement (see below);
* **transmitted content** (water, glass, ice): the guide tuple describes the refracted
  *destination*, which is deliberately off the pixel's ray — `delta` is that lateral
  offset, and both ends then follow the destination.

The lateral offset is fp32 on purpose. It is added to the depth-reconstructed point to
form the current end, so its absolute error lands straight in the motion vector; in fp16
a distant or grazing water surface (where the offset is metres of world space) would put
a ULP of rounding noise into the vector of every such pixel, every frame. The own-motion
vector feeds the previous end alone and stays fp16 — one frame of travel is a couple of
blocks at most.

Sky is special-cased to zero: `gViewZ` forces every sky pixel to the same 1e6 sentinel
while the guide point sits at `1e6 * cos(angle to the view axis)`. The difference is a
pure scale along one ray (identical screen position) and does not fit in fp16.

## Precipitation

Vanilla's rain and snow are not moving geometry. `WeatherEffectRenderer` emits a fixed
vertical sheet per column and advances the *sampled V coordinate*
(`v = y * 0.25 + vOffset`): the texture scroll **is** the falling motion, and the sheet's
vertices never move. A capture that diffs vertex positions therefore reports a zero
displacement — which is exactly why the streaks froze into static lines the moment the
camera stopped, because a zero motion vector tells every temporal filter that the pattern
is still there.

`RtWeatherCapture` diffs each column's `vOffset` frame to frame (keyed by column, so it
survives the camera crossing a chunk border, with a wrap guard and a jump guard) and
publishes the equivalent world-space travel: one V unit is four blocks, and an increasing
`vOffset` moves the pattern **down**. `RtEntities` writes that value into the weather
vertices' entry of `particleDisp`, so it reaches the guide as an ordinary particle
displacement and flows through the shared path.

## DLSS-RR history invalidation on geometry edits

RR only ever rejects history through a soft depth/normal weight. With correct, static
motion vectors its history is fully trustworthy, so geometry that did not exist last
frame — a block or sign the player just placed — can take dozens of frames to reach full
strength: the classic "the block takes forever to appear while I stand still" report,
which FSR/XeSS do not show because they do not accumulate a denoised signal.

The renderer knows something RR cannot: a block edit happened this frame
(`RtTerrain.geometryRevision()`, bumped by the edit hooks). `RtComposite` asks RR to drop
its history for that one frame when the camera did **not** move or rotate, which puts the
new content on screen immediately at the cost of a single noisier frame. While the camera
moves the reset is skipped: RR is already refreshing the image, and building continuously
would otherwise cost a reset every time a block lands.

## Invariants (pinned by `RtWorldReprojectionTest`)

1. `guides.slang` must not write `gMotion`; `world_reproject.comp` is its only producer.
2. Both sides build the 3D point through `cameraRelativePointAtViewDepth` — no second
   inverse-projection implementation.
3. **Both ends are projected.** The current end is `project(shown, curViewProj)`, never
   `jndc`: subtracting the pixel's own NDC is what reintroduces the refraction offset as
   fake motion.
4. The vector is `(previous - current)` in render pixels, with `prevViewProj` (the
   *previous* frame's projection, carrying its FOV) and the frame's `WorldPushConstants`
   block — the same matrices and jitter the trace used.
5. `RtComposite` dispatches the reprojection after Pass A and before NRD/SVGF/DLSS-RR.

`gViewZ` must stay written unconditionally (it used to be gated on NRD-or-SVGF being
active); it is now the reprojection's depth source on every path.

## Debugging

Debug view **5 (Motion)** is painted by `world_reproject.comp`, because the final vector
does not exist until that pass runs. The fallback guide view shows `|gReprojDelta|` in
red: a nonzero value over static geometry is the shape of a reprojection bug.
