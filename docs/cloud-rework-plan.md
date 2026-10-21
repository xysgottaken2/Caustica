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

---

## Status (2026-10-19) — volumetric shape model replaced

The volumetric style no longer draws a noise field thresholded inside a slab. It generates a **3D
density distribution** per formation. The classic style is unchanged (authored `clouds.png` cells,
analytic boxes); everything below applies to `CLOUD_STYLE_VOLUMETRIC` only.

### What the old volumetric deck was

Two things stacked:

1. `cloudCoverageField` — 2D value noise, thresholded by coverage. Every sample in a column shared it,
   so the cloud had one constant cross-section from base to top.
2. Inside the slab, `cloudVolumeDensity` multiplied that 2D mask by a **vertical profile** (taper at
   top and bottom) and added billow noise on the same 12-block lattice. The profile is what made the
   deck read as *layers*: the silhouette was the 2D field's silhouette, extruded, with a soft top.

### What it is now

Ladder (all powers of two, so every lattice still divides the anchor wrap exactly):

| field | period | role |
|---|---|---|
| weather | 192 blocks | where cloud regions are, with real gaps |
| formation | 96 blocks | per-formation thickness ∈ [min, max] and base offset (0..4 blocks) |
| warp | 96 blocks | 2D domain warp, growing with height, so masses shear asymmetrically |
| shape | 24 × 12 blocks (Y compressed) | Perlin fBm 3D: the large rounded body |
| lobe | 12 × 8 blocks (Y compressed) | Worley fBm 3D (correct 27-cell F1): cauliflower, cavities |
| detail | 12 blocks | Worley erosion, faded with distance (unchanged) |

Per sample: the coverage field defines the region *and* a `core` gradient (0 at the region's edge, 1 at
its centre) that lowers the isosurface threshold, so a formation is dense inside and ragged at its rim.
A per-formation vertical envelope (`CLOUD_CROWN_START` ± `CLOUD_CROWN_VARIANCE`) raises the threshold
toward each mass's own crown and base, so every cloud closes at its own height instead of being cut by
the slab. Shape is `lerp(perlin, worley, 0.50)`; erosion removes a fraction proportional to
`(1 - density)`, which thins the shell and leaves dense cores intact.

The Y anisotropy is the single most important change for silhouette: with an isotropic lattice a
24-block period over a 16..22-block slab is one feature tall, i.e. a wall. Compressing Y makes each
lobe roughly 12 blocks tall and 24 wide — oblate, rounded, cumuliform.

### Thickness sliders

`Minimum Cloud Thickness` and `Maximum Cloud Thickness`, 16..22 blocks, in the volumetric section of
the existing clouds screen, persisted as `composite.cloud-min-thickness` / `composite.cloud-max-thickness`
and overridable with `-Dcaustica.rt.cloudMinThickness` / `-Dcaustica.rt.cloudMaxThickness`.

- `CausticaConfig.Rt.Composite.setCloudMinThickness/setCloudMaxThickness` keep the pair ordered by
  pushing the dependent value, so the invariant holds for the slider, TOML, system property and reset
  paths alike; `reconcileCloudThickness()` repairs a hand-edited file once per frame.
- `RtComposite.cloudState` pushes the pair in `WorldPush.cloudShape.xy` (a new float4 lane; the struct
  stride is now 928 bytes, generated from Slang reflection as before) and sizes the march slab from the
  maximum plus `CLOUD_BASE_JITTER_BLOCKS`.
- `clouds.slang:cloudFormationThickness` lerps between them per formation, so equal values give every
  formation exactly that thickness and a range gives a natural mixture.
- The legacy 0..100 % thickness slider is now shown **only** in the classic style, where it still
  scales vanilla's box height; in volumetric it would control nothing, so it is hidden rather than left
  as a dead knob. Coverage remains volumetric-only (classic bakes coverage into the texture).

### Animation

The wind drift is unchanged (0.03 blocks/tick, vanilla parity, anchored/wrapped exactly as before).
On top of it, `cloudShape.z` slides the 3D lattices through Y at 0.15 blocks/s, wrapped to
`CLOUD_MORPH_PERIOD_BLOCKS` (24576 = a common multiple of every displaced lattice's Y period:
12288 for the shape and detail bands, 8192 for the lobes). The rate was chosen
from measured volume coherence: ~97 % of a cloud's volume is in the same state 1 s later and ~84 %
after 5 s — it billows over tens of seconds instead of boiling (1.25 blocks/s, the first value tried,
gave 80 % / 51 %).

### The "cut off" silhouette (reported after the first pass)

Coverage enters as a **threshold on the 2D weather field**, and a threshold is a level set: samples just
inside it can carry full density while samples just outside carry none. So wherever that contour ran, a
cloud ended in a near-vertical wall. It was worst at low coverage — a region is then only a couple of
noise features across (median 113 blocks at 35 % coverage) against lobes 12–24 blocks wide — so the wall
was a large fraction of the cloud. Measured before the fix at 35 % coverage: full density (1.00) sat
directly on the contour, and 10–28 % of a scanline's cloud edges lay exactly on it.

Fixed by making the per-formation envelope close horizontally as well as vertically:

```
envelope = smoothstep(0, 0.12, hf) * (1 - smoothstep(crown, 1, hf)) * smoothstep(threshold, threshold + CLOUD_RIM_BAND, weather)
```

`CLOUD_RIM_BAND = 0.042` is 8 blocks at the 192-block weather period. Because the envelope already
reaches 0 where the mass closes, multiplying the rim term in makes the density zero *at* the contour
too, so the silhouette is the 3D noise's own isosurface and cannot be sliced by a 2D curve. After the
fix, peak density on the contour is 0.00 at every coverage tested, and no scanline edge sits on the
contour.

Two things were rejected first:

- **A world-space fade** (distance = value / |grad(value)|) gives the same closure with an exact
  physical width, but needs two extra coverage evaluations per density sample (compared against
  ±2 blocks). At 10 march steps x 5 light steps that is 20 % more coverage noise on the hottest path
  in the renderer. The field is smooth at a 192-block period, so over 8 blocks the constant-band
  approximation differs by a few percent.
- **Removing the threshold entirely** (weather biasing the isosurface instead) removes the contour
  completely and first looked attractive, but it re-parameterises the whole coverage curve: to keep
  the slider meaning the same thing the bias has to be `coverage * (0.5 - weather)`, which puts the
  contour back — just rotated — and without it the sky saturates (100 % covered from ~0.6).

Cost: a thin edge is thinned before it closes, so sky coverage seen from above drops a little, and
slightly more in the middle of the slider than at its ends: 0.4 / 16.5 / 52.5 / 84.7 / 97.3 % at
slider 0.2 / 0.4 / 0.6 / 0.8 / 1.0, measured with the rim, versus a threshold that saturates at ~97 %
by 0.8 without it. Full overcast still closes the sky.

### Lighting

Unchanged in structure — per-step light march through the same density, dual-lobe HG, Beer-Lambert,
three-term multiple scattering, powder term — but it now integrates real 3D density, so self-shadowing
between lobes is what darkens undersides and cavities. Two follow-ups: the light march takes a
single-octave density (the optical path through a lobe is a bulk property; the second octave was pure
cost), and `cloudPowder` now returns the edge weight directly (`exp(-4ρ)`, 1 at thin edges) matching how
the caller mixes it, instead of a duplicate of the brightening term.

### Verification performed

- `slangc` (the compiler bundled in the `slangpy` wheel, driven through its C API — there is no Vulkan
  SDK here) compiles every file under `shaders/world` clean, including `clouds.slang`,
  `world.rgen/rmiss/rchit/rahit` and `world_layout_probe.slang`. Reflection of `world_layout_probe` reports stride **928** with
  `cloudShape` at **672** and `cloudCellsAddr` at **688**, which is what the regenerated
  `WorldPushData` will have.
- An offline numpy transcription of the exact density function (same uint32 hash, same lattice
  periods, same stage order; constants parsed out of `clouds.slang` so it cannot describe an older
  tuning) measured the field over a 2 km x 2 km grid:
  - configured thickness over the interval: 16–22 → mean 19.20, std 2.23; 16–16, 22–22 and 19–19 →
    exactly constant (std 0.00). Nothing ever falls outside the configured pair.
  - per-column vertical extent above the formation's *own* base never exceeds that formation's
    thickness. Observed maxima: 13.61 blocks for a 16-block cap, 18.04 for 22, 16.50 for 20. The
    extents are *below* the ceiling because the crown envelope closes the mass first — that is the
    point, and no column reaches its ceiling at all (0.00 % within 0.25 blocks of it).
  - extent above the deck's base plane peaks at 22.00 blocks, well inside the 26-block slab the
    renderer pushes (`maxThickness + 4`).
  - density: 19.0 % occupancy inside formation bounds, 7.9 % saturated, mean 0.76 where present
    (p10/p50/p90 = 0.23/0.95/1.00) — dense cores, wispy rims.
  - rim: sampling columns that sit exactly on the coverage contour (85–125 of them per coverage level,
    at 0.30/0.35/0.45/0.60) finds peak density **0.00** — no cloud is cut by the contour.
  - animation coherence (IoU of the occupied volume): 0.978 after 1 s, 0.894 after 5 s, 0.619 after
    30 s at the 0.15 blocks/s churn rate.
- Ray-marched previews of the transcription (under the deck and from above) were rendered and
  inspected for silhouette: rounded, asymmetric, non-repeating masses with cauliflower edges, no
  visible horizontal banding or repeated blobs. This is a contrast check on the density field, not a
  picture of the game's shading.
- `CLOUD_ISO_TOP` was raised from 0.72 to 1.0 after this measurement. At 0.72 a thin tail of columns
  (0.1 %) still reached its exact ceiling — a flush top, the layered silhouette this rework removes.
  At 1.0, which is the top of the shape field's own range, `smoothstep(1.0, 1.0 + SOFTNESS, shape)` is
  identically zero at the crown and the base, so closure is structural rather than statistical, and
  the measurement above shows no column within 0.25 blocks of its ceiling. Cost: ~0.3 block of mean
  vertical extent.
- Tests:
  - `RtCloudThicknessTest` pins the config contract (ranges, defaults, both push directions, equal
    endpoints, out-of-order repair, registration) and the shader wiring (lerp between the two lanes,
    density identically zero outside `[base, base + thickness]`, slab sizing, `WorldPush` lane,
    independence from view distance).
  - `RtCloudShapeModelTest` recomputes the period ladder from the shader source: every divisor is a
    power of two, and `CLOUD_MORPH_PERIOD_BLOCKS` is a whole period of every anisotropic Y lattice.
    It found a real defect while being written — the lobe band's Y period is 8192 blocks, which did
    **not** divide the 36864 chosen by inspection, so the churn wrap would have snapped the
    cloudscape every ~8 hours. The period is now 24576 (2 x 12288 = 3 x 8192).
- **CI passed** on the pull request's first revision: `compileJava` against Minecraft 26.3 (which
  also runs `generateShaderRecords`, and therefore builds the 928-byte `WorldPushData`), the full
  `test` suite, `verifyMinecraftAbi`, `jar verifyModJar`, and the bundled-JAR job that compiles every
  shader with `slangc`. So the Java compiles, the generated push record matches the shader's reflected
  layout, and the new tests run and pass.

### Not verified

- **The game was not launched.** No rendering behaviour was observed: not the deck's look from any
  camera angle, not the shader's cost, not the sliders in the real UI. CI explicitly does not touch a
  GPU.
- The previews used to judge silhouette and lighting are offline ray-marched renders of the numpy
  mirror described above, not Minecraft frames.

## Cirrus sheet above the volumetric deck

Cirrus are the thin, fibrous ice clouds that streak across the sky well above a cumulus deck —
"various threads on top of the normal cloud", which is exactly the shape this adds.

They are a **second layer**, not part of the marched volume. Folding them into
`cloudVolumeDensity` would have meant lobes, cavities and self-shadowing applied to a cloud type
that has none of those, and it would have put the ice inside the same 16–22-block slab as the
cumulus. Instead:

- `cirrusEnabled` requires the deck itself to be on *and* `FEATURE_CIRRUS`, which
  `RtComposite.featureFlags()` only sets for the **volumetric** style with the player's toggle on.
  The classic deck is a flat authored texture with nothing above it, and the clouds screen hides the
  row in that mode, so the setting is remembered rather than silently applied.
- Altitude is `clouds.w + thickness * 0.5 + 110`, i.e. a fixed gap above the deck's **crown**: Cloud
  Height, the Min/Max thickness pair and a storm's vertical growth all lift the sheet, and the two
  layers can never intersect.
- The field (`cloudCirrusCoverage`) is 16:1 anisotropic — 192-block filaments along X (the direction
  the deck already drifts, and where the high-altitude wind points) against 12-block strands across
  it — with a domain warp that bends the filaments, a finer cross-noise that carves gaps between
  them, and a 192-block patch field deciding which regions of sky carry cirrus at all. It is *not*
  the cumulus coverage field: the sky above the deck gets its own shapes.
- Every lattice divisor is a power of two no coarser than `CLOUD_WEATHER_DIV`, so each period in
  blocks divides the 196608-block anchor wrap and the sheet stays seamless as the wind scroll rolls
  over. `RtCirrusCloudTest` recomputes this.
- Shading is single-scatter only (dual-lobe phase × sun radiance + a sky fill): the sheet is far too
  tenuous for a multiple-scattering sum, and its alpha grows with the path length through its own
  thickness, so it brightens into a band toward the horizon. `Cloud Opacity` caps it like the deck,
  and a storm fades it out.
- `cloudSegment` composites the two layers **far-to-near** off their analytic mid-plane distances
  rather than assuming the deck is in front: a camera above the sheet crosses the cirrus first, and a
  hardcoded order would draw cumulus through a veil that should hide it.
- Cirrus cast **no** ground shadow. The Cloud Shadows slider keeps describing the cumulus footprint
  it was tuned against.
