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
pure scale along one ray (identical screen position) and is meaningless at 1e6 blocks, so
`writeGuides` zeroes both vectors for `isSky`. Sky then reprojects as the rotation-only,
effectively infinite point it is: a still camera gives it exactly zero motion, and a
turning camera gives it pure rotation.

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

## The viewmodel

The hand is captured camera-relative: vanilla composes its pose as `V^-1 * bob * model`
and the raster pass re-applies `V`, so the world-to-view rotation cancels and the vertices
arrive as offsets around the eye, placed by a translate-only TLAS instance
(`capture`: `RtViewmodel.capture`, instance: `RtViewmodel.instanceTransform`).

That changes the shift inside the displacement formula. Every rebased capture computes

```
disp = (cur - prev) + (anchor_cur - anchor_prev)
```

and the viewmodel has no terrain anchor: its "anchor" is the camera, so

```
disp = (cur - prev) + (camera_cur - camera_prev)
```

An earlier revision did both halves of this wrong — it *subtracted* the camera delta and
*added* `previousRb - rb`, an anchor term that cannot move camera-relative geometry at
all. The hand sits half a block to a block from the eye, where one block of camera travel
projects to several hundred render pixels, so a walking step produced a ~400 px vector
(`ViewmodelDisplacementTest` pins the numbers: at 0.62 blocks of depth, a 0.4-block step
gives -408 px on the old sign, 0 px on the correct one). Every temporal filter then saw a
mesh that teleported each step and smeared its history across the hand — the scratches
reported while walking.

A changed vertex count (an item swap) is *not* "no motion": the hand still travels with
the eye, so a mesh with no predecessor reports the camera's own travel. That reprojects
to (almost) zero screen motion, which is what the screen shows. Reporting zero there — or
returning no displacement at all, which the reprojection reads as "world-static" — would
hand every filter a scene-sized vector at hand depth on the first frame of the swap.

## DLSS-RR history policy

Correct static vectors make RR's history *the* thing to keep: that is what accumulates 1
spp into a clean image. They also make it hard to change: RR rejects history only through
a soft depth/normal weight, so geometry that did not exist last frame — a block or sign
the player just placed — is blended in against a confident, converged history and can
take dozens of frames to reach full strength. FSR and XeSS do not show this because they
never accumulate a denoised signal.

NGX has no per-pixel reset, so the only lever is `InReset`, which costs exactly one raw
1-spp frame. The policy therefore has to choose *when* that frame is worth it, and it is
deliberately neither "keep everything" nor "reset on anything":

* **Any edit the player can see earns a reset, whether or not the camera is moving.**
  An earlier revision only reset while the camera was still, on the theory that a moving
  camera refreshes the image anyway. It does not: the edit changes the picture in a place
  the motion vectors say is static, so a sign placed while crossing a room faded in over
  a second. `RtComposite.maybeInvalidateRrHistoryForEdits` reads the last block-change
  request (`RtTerrain.lastBlockEdit()`, the box the edit hooks forwarded) and resets when
  its nearest block is within `RR_EDIT_RESET_RADIUS` (32 blocks) of the eye **and** its
  centre projects into the frame.
* **Ambient churn does not.** That same dirty counter fires for flowing water, crop
  growth, fire and every block a server tick touches — anywhere in the loaded world.
  Resetting on those replaced the whole converged image with one noisy frame several
  times a second, worst exactly while standing still and looking at the scene that was
  changing. The distance and frustum tests are what separate "the block I just placed"
  from "a river three chunks away".
* **Resets are rate-limited.** `RR_EDIT_RESET_COOLDOWN_FRAMES` (4) caps how often a reset
  can fire, so a build spree, a fast break, or a waterfall at the player's feet cannot
  strobe the image. A lone edit is never delayed — the cooldown only throttles the ones
  that arrive inside the window — and an edit consumed by the cooldown is dropped rather
  than replayed later, when its noisy frame would land on content that has already
  settled.

`RtFrameStats` counts the resets as `rrHistoryResets`; a nonzero steady rate while
standing still in a still scene is a bug in this policy, not in the vectors.

## Invariants (pinned by `RtWorldReprojectionTest` and `ViewmodelDisplacementTest`)

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
6. A capture's displacement is a **world** displacement: `(cur - prev) + shift`, where the
   shift re-expresses last frame in this frame's space — the rebase shift for rebased
   geometry, the camera's travel for the camera-relative viewmodel. The viewmodel must not
   add an anchor term, and a hand with no predecessor reports the camera's travel, never
   zero (`RtViewmodel` / `ViewmodelDisplacement`, pinned by `ViewmodelDisplacementTest`).

`gViewZ` must stay written unconditionally (it used to be gated on NRD-or-SVGF being
active); it is now the reprojection's depth source on every path.

## Debugging

Debug view **5 (Motion)** is painted by `world_reproject.comp`, because the final vector
does not exist until that pass runs. The fallback guide view shows `|gReprojDelta|` in
red: a nonzero value over static geometry is the shape of a reprojection bug.
