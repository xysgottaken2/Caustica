# Cloud rework — findings and plan

Working notes for the second half of the weather work. Part 1 (rain fixes) is in
`RtWeatherCapture`; this file covers the clouds.

## Why the clouds keep producing new bugs

The current deck is a **procedural noise field** (`cloudCoverageField` in `shaders/world/clouds.slang`):
three octaves of value noise on a 12-block lattice, thresholded by the coverage slider. Every visual
property — where a cloud is, how big it is, whether the sky is covered — is an emergent consequence of
that noise plus roughly a dozen interacting tuning constants (`CLOUD_EDGE_SOFTNESS`, `CLOUD_BULGE`,
`CLOUD_VOLUMETRIC_SCALE`, `CLOUD_BILLOW_DIV_*`, `CLOUD_MAX_SLAB_CROSSINGS`, the two extinction scales…).

That is the structural reason fixing one bug tends to surface another: the constants are not
independent. `CLOUD_VOLUMETRIC_SCALE` must stay a reciprocal power of two or the field desyncs at the
anchor wrap; the classic style's `+ CLOUD_EDGE_SOFTNESS * 0.5` threshold offset exists only to keep the
two styles covering the same fraction of sky; the opacity slider had to be moved out of the extinction
and applied to the finished march because it was silently doubling as a thickness multiplier. Each of
those is a real fix, and each one couples two more knobs together.

**Vanilla does not have this problem, because vanilla does not generate a field.** It reads
`textures/environment/clouds.png` — a 256x256 image where each pixel is one 12-block cell — and extrudes
the non-transparent ones. Cloud *shape* is authored data, not a tuning problem.

## What vanilla actually does (26.2 `CloudRenderer`)

Verified against the 26.2 source, not from memory:

- `prepare()` reads `textures/environment/clouds.png` into `long[] cells`, one entry per pixel.
  `isCellEmpty(color)` is `ARGB.alpha(color) < 10`. Each cell packs its colour plus four
  "is my neighbour empty" bits (`packCellData`), which is how it culls interior faces.
- Cell size is `CELL_SIZE_IN_BLOCKS = 12.0F`; the extruded box is **4 blocks tall** (`putVec3(12, 4, 12)`).
- Scroll: `cloudOffset = gameTime % (width * 400L) + partialTicks`, then `cloudX = cameraX + cloudOffset * 0.030000001`,
  and `cloudZ = cameraZ + 3.96`. The texture tiles every `width * 12` blocks, so the pattern wraps
  seamlessly and is *exactly periodic* by construction — no anchor-wrap hazard at all.
- `CloudStatus.FANCY` extrudes boxes; `FAST` draws only the down face.
- Colour comes from `EnvironmentAttributes.CLOUD_COLOR` (an ARGB the game already resolves per-dimension
  and per-weather), and height from `EnvironmentAttributes.CLOUD_HEIGHT`.

## Reported bugs and their likely causes

1. **A horizontal "line" that follows the camera and brightens ~12 blocks around it.**
   Almost certainly `cloudTrace`'s crossing fade combined with the slab entry/exit in `cloudMarch`. When
   the camera is near the deck plane, `deckRel` approaches zero and `t = deckRel / dir.y` collapses, so a
   band of near-horizontal rays all sample essentially the same point at very short distance. The
   `crossFade` smoothstep was added to soften exactly this, but it fades *coverage*, not the march's
   in-scatter, so the band still accumulates light. Needs the slab to be excluded outright within the
   crossing region rather than merely faded.

2. **Classic clouds stay white in rain.** `cloudRadianceClassic` and `cloudMarch` both use the hardcoded
   `CLOUD_ALBEDO = (0.94, 0.95, 0.98)`. Nothing in the classic path reads the weather lanes.
   (PR #25, still open, adds a `cloudAlbedo(push)` helper that greys it by
   `weather.x + weather.y * 0.5` — same idea; worth reconciling rather than duplicating.)

3. **Sky never fully covers in rain — holes remain.** `cloudState` ramps
   `overcast = rain * 0.85 + thunder * 0.15`, and coverage is `coverage + (1 - coverage) * overcast`, so
   full rain with no thunder tops out at 0.85 rather than 1.0. Worse, the classic style then quantises
   with `> threshold + EDGE * 0.5`, so cells sitting just under the threshold stay empty and read as
   punched holes. PR #25 also touches this (drives coverage *and* opacity to 1 and adds a
   `CLOUD_SOLID_COVERAGE` short-circuit).

4. **Water not affected by rain.** Fixed in part 1 — the cloud/atmosphere prefix is composited in
   `world.rgen.slang` once the camera→interface hop is recovered.

## Performance (30-40 → 10-20 fps on an RTX 2060)

The volumetric march is the cost, and it multiplies badly:

- `cloudMarch` runs up to `CLOUD_MARCH_STEPS_MAX = 48` steps, and **each step** calls
  `cloudLightTransmittance`, which is itself a loop of up to `CLOUD_LIGHT_STEPS = 6` density samples.
  Each density sample (`cloudVolumeDensity`) evaluates the coverage field plus a domain warp plus two
  billow octaves — roughly 6 `cloudNoise` calls, each of which is 4 hashes and two lerps.
- Worst case per ray: `48 * 6 * ~6` noise evaluations ≈ **1700 hashed lookups**, before SPP.
- That is then paid again per sample-per-pixel, and once more for every specular bounce that selects the
  full-quality path (`showCelestial`), which is exactly what water and glass do.

Cheapest large wins, in order:
1. Replace the procedural field with a **texture fetch** (vanilla's cell map). One sample replaces ~6
   noise evaluations, and the GPU's texture cache is well suited to the access pattern.
2. Precompute the light march. With authored cells the deck's optical depth from above is a *2D* value —
   it can be baked once per frame into a small buffer instead of marched per step.
3. Make the classic style a genuine analytic box intersection again. Authored cells are axis-aligned
   boxes; a slab/AABB test is exact and needs no march at all.

## Plan

- Upload vanilla's `clouds.png` cell map (256x256, one byte per cell: occupied + neighbour bits) into the
  existing world-push BDA ring, alongside the pattern `READY_MASK` already uses. Mirrors how the DH ready
  mask is transported, so no new binding is needed.
- `cloudCoverage`/`cloudCoverageField` become a wrapped lookup into that map. The field stays exactly
  periodic for free (the texture tiles), which removes the whole class of anchor-wrap bugs and lets
  `CLOUD_VOLUMETRIC_SCALE`, `CLOUD_WARP_DIV` and `CLOUD_BILLOW_DIV_*` drop their power-of-two constraint.
- Classic: analytic box, 4 blocks tall at minimum, **thickness slider scales the height** (user's choice —
  vanilla shapes, slider-driven depth, never thinner than vanilla's 4).
- Volumetric: keep the march but drive it from the authored coverage, and reserve the tall towers for
  cells whose neighbourhood is fully occupied, so only genuinely large cloud masses get vertical
  development ("only some thick clouds").
- Colour from `EnvironmentAttributes.CLOUD_COLOR` so rain greying is vanilla's own value rather than
  another hand-tuned ramp.
- Fix the camera-plane band by excluding the slab within the crossing region instead of fading coverage.

## Coordination note

PR #25 ("Fix storm cloud coverage and rain atmosphere") is still open and already addresses bugs 2 and 3
with small targeted patches, and also raises `rainLight`/`rainSky` in `weatherState`. Part 1 of this work touches `weatherState`'s neighbourhood
but not those lines. Before starting the rework,
decide whether to merge #25 first and build on it, or supersede it — doing both independently will
conflict in `cloudState` and `clouds.slang`.

---

## Status (2026-08-07) — rework implemented on arena/019fddc8-testingcasutica

- **Classic style now reads `clouds.png`** (RtCloudCells → bit-packed occupancy map in the BDA push
  ring, addressed from `WorldPush.cloudCellsAddr`). With thickness > 0 the deck is intersected
  analytically (`cloudClassicBoxes`): per-cell AABBs, grid walk, no march. The noise-quantised classic
  path remains only as the fallback for a missing/non-256x256 texture.
- **Colour is `EnvironmentAttributes.CLOUD_COLOR`** (`WorldPush.cloudColor.xyz`), read through the
  camera probe like the sun/star angles — bug 2 (white deck in rain) is fixed by the game's own value.
- **Bug 3 fixed**: `cloudState` pushes the weather fill as its own lane (`cloudColor.w`), ramping to 1
  on rain alone; both styles reach full cover — the classic style by progressively filling authored and
  empty cells with stable per-cell hashes, the volumetric style via the coverage threshold with a hard
  100% short-circuit.
- **Bug 1 fixed**: `cloudMarch` applies the flat path's crossFade to the slab's sigma — the deck is
  optically *excluded* inside the crossing region, so the band can no longer accumulate in-scatter.
  The analytic classic path has no equivalent degeneracy (no mid-plane shading).
- **Sun/moon discs fade out with the rain lane itself** (`world.rmiss`), no longer betting on the deck
  covering them.
- **Thickness slider**: classic floors at vanilla's 4-block box height and scales up; volumetric keeps
  the full 0..110 range. Deck anchor Z offset matches vanilla's 3.96.
- **Volumetric style untouched** per the scope decision: same field, same shape, same constants. Its
  performance ideas from this plan (precomputed light march, cheaper diffuse bounces) are pending user
  sign-off before any change.
