# Procedural volumetric clouds: geometry, integration and validation

## Investigation and scope

The quality/shading follow-up continues **PR #13** from `c74ec2a`; it does not replace the density
system or change the classic renderer. Details of the five presets and their measured work are below.

The starting implementation (`9e00b4e`) already had procedural volumetric clouds; it did **not**
read PNGs. `cloudHash` is a deterministic 512-cell integer hash, `cloudNoise` is 2D smoothstep value
noise, and `cloudCoverageField` uses frequencies **1/2/4**, with **weights** 0.6/0.3/0.1 on a 12-block
lattice. The old volumetric profile combined this coverage with height-driven 2D warp/billows.

The primitive period is **6144 blocks**, but `RtComposite` already wrapped the wind + camera anchor
at **24576 blocks**, to accommodate its slower octaves. The old header's claim that the host wrapped
at 6144 was stale. The complete period, the 0.03-block/tick wind and the classic 3.96 Z offset are
unchanged. No new texture, buffer of density samples, parallel cloud system or extra rendering pass
has been introduced.

The path remains:

1. `CausticaConfig` → `RtComposite.cloudState` → reflected BDA `WorldPush`.
2. `world.rmiss` composites `cloudLayer` over atmosphere/celestials.
3. `world.rgen` also integrates `cloudSegment` **before terrain hits**, for primary and secondary rays,
   and for the recovered camera-to-water-interface prefix. Scatter/transmittance stay premultiplied.
4. Direct lighting and fog call `cloudSunShadow` after scene visibility has survived.

Classic still reads the original `clouds.png` occupancy bitmap and uses analytic boxes with vanilla
face colour/shading. Its old percentage thickness and missing-map fallback remain. One small classic
fix was necessary after executable tests exposed an existing disagreement: analytic boxes ignored
weather fill even though their shadow/coverage query applied it. They now share that stable per-cell
weather decision; **clear-weather authored box occupancy is unchanged**.

## Shape construction

- **Weather organisation:** keep the existing value-noise coverage field and slow 2D weather warp.
  Rain fill now reaches the *marched density*, not just a separate coverage query.
- **True 3D structure:** periodic quintic-interpolated gradient (Perlin) noise connects volumes;
  a jittered 3D Worley F1 field supplies rounded 12-block lobes.
- **Envelope deformation:** a low-frequency **three-component** domain warp bends the large-scale
  coverage as well as the smaller lobes. Warping detail alone left nearly straight-sided columns in
  the first CPU diagnostic renders; the envelope now bends with height too.
- **Vertical development:** a relatively sharp condensation base, broader middle and a crown whose
  width closes to **zero** at the local top. An initial nonzero crown width produced clipped, flat
  mesas in diagnostic slices despite passing the numeric height bounds; this was corrected.
- **Secondary/fine structure:** 6-block cellular erosion breaks the surface into smaller scallops;
  3-block gradient erosion frays their edges. Erosion only **removes** material and is strongest at
  the boundary. Low-frequency type/interior modulation prevents a constant-density cotton core.
- **Variation:** regional thickness, crown shape, base softness, density and erosion differ smoothly.
  No per-cell discontinuous height choice or independent octave animation is used.

All horizontal wavelengths (48/24/12/6/3 blocks) divide 24576 with the 512-cell hash. The Worley
27-cell search is complete: feature offsets lie in [.28,.72], so the own-cell distance is at most
`sqrt(3)*.72 < 1.28`, less than the minimum distance to any cell outside that neighbourhood. Neighbours
entering/leaving the stencil therefore cannot introduce jumps. No rotation or nonintegral frequency
of the world coordinate breaks the periodicity.

## Thickness contract and configuration

The volumetric page has independent integer-block controls:

| Control | TOML key under `[composite]` | Default | Allowed |
| --- | --- | --- | --- |
| Minimum thickness | `cloud-min-thickness` | 16 | 16–22 |
| Maximum thickness | `cloud-max-thickness` | 22 | 16–22 |

The system property names are `caustica.rt.cloudMinThickness` and
`caustica.rt.cloudMaxThickness`. The existing TOML save/load, property precedence and reset registry
are used. English and Brazilian Portuguese labels/tooltips explain the units and ordering rule;
other locales fall back to the English keys.

- Editing minimum above maximum raises maximum. Editing maximum below minimum lowers minimum.
  The last edited endpoint wins, and the UI rebuilds dependent rows **only when the peer changed**,
  following the existing screen-reopen pattern. Setter, reset and property-reload paths enforce it.
- Both persisted inputs are clamped before linking them. In an invalid hand-edited file the
  **maximum**, loaded second, wins (22/18 becomes 18/18). This is deterministic and documented in code.
- `cloudFormationThickness` interpolates the endpoints using a continuous regional distribution;
  there is no hard cell boundary. Equal endpoints return that exact constant before sampling noise.
- This is the **geometric envelope parameter**, not a promise that every wispy edge or every vertical
  density column has visible material over 16 blocks. Erosion, rounded crowns and small fragments
  necessarily occupy less than their envelope. Tests measure both quantities separately.
- Every density query is clipped to `[base, base + localThickness]` **before** applying noise/warp.
  Nothing can grow below the base or above the local envelope, hence nothing can exceed the maximum.
  No vertical domain warp is applied to the envelope itself.
- The acceleration slab uses the maximum. Java adds half its depth to the configured base altitude,
  so changing the maximum does not move the base. The new float4 is appended to the generated BDA
  record; the already-full 128-byte hardware push-constant block is untouched. Its previously reserved
  Z lane now carries the quality id 0..4; this follow-up does not grow the GPU/Java record.
- Old `cloud-thickness` is preserved and still controls classic's 4–110-block boxes. It no longer
  scales the volumetric envelope. The old default was 55 nominal blocks, outside the requested range;
  16/22 uses the largest permitted upper bound and immediately enables natural regional variation.
  Old files without the new keys get these defaults; switching styles does not destroy old settings.

## Lighting and marching

A common `cloudOpticalDensity` now supplies eye extinction, light-marched self-shadow and volumetric
ground shadows, normalised by **local**, not maximum, thickness. The previous light march used a
different coefficient and the ground shadow sampled an unscaled footprint. Opacity remains a final
ceiling, so a 25% slider can never become more than 25% opaque at grazing angles.

Sun and sky optical depths now drive the grey shading separately:

- Direct sunlight uses the actual active celestial direction and `exp(-sunTau)`.
- The sky term integrates **actual material overhead**, up to the local envelope top, instead of
  multiplying only the point's density by its distance to the top. A thin skin below a thick lobe
  is therefore correctly shadowed even though its own density is low.
- Sky visibility is `0.06 + 0.64 * exp(-skyTau)`. The small floor is unresolved hemispherical fill,
  multiplied by incident sky radiance: it prevents artificially black undersides without making an
  unlit cloud emissive. No screen coordinate, distance to the Sun or painted grey texture is involved.
- The dual HG phase and powder approximation remain, but phase lobes are evaluated once per ray.
  Powder reuses sun transmittance instead of another exponential. Higher presets retain one/two
  weaker, more isotropic scattering orders, attenuated by sun **and** sky optical depth to avoid
  washing out dense interiors. This remains an approximation, not a full multiple-scattering solver.
- Day/night colour and weather still come from the existing celestial/atmosphere inputs.

The near-point light march retains quadratic strata with larger budgets. At two samples it uses
Gauss–Legendre positions/weights instead: the old two quadratic intervals assigned 75% of the weight
to a single far sample. Ground/fog cloud shadows use uniform spatial intervals over the bank, with
separate bounded budgets, and terminate below .00034 remaining direct-light transmission.

Slab bounds remain camera-relative, including secondary rays. Real volumes never dissolve at the
camera's deck-centre crossing. The earlier origin-Y fix and residual-transmission early termination
are preserved. The 3.5-slab-depth integration limit and view fade also remain: **extremely grazing
rays can still omit distant banks**. Test the horizon and in-cloud views in Minecraft.

## Five quality presets

Persistent key: `[composite] cloud-quality`, property: `caustica.rt.cloudQuality`. Values are
`low`, `medium`, `high`, `very_high`, `ultra`; UI labels include **Baixo, Médio, Alto, Muito Alto,
Ultra**. Missing/unknown values resolve to **Ultra**, preserving the previous full-detail default.
The selector is shown only for volumetric clouds, participates in resets, and takes effect on the next
frame. It never changes thickness, wind, weather or the retained classic settings.

**Intervals and evaluations are not the same thing.** Low and Medium use two cheap density probes
per visible integration interval (two-point Gauss–Legendre quadrature), then perform lighting once
at their material-weighted centroid. This was necessary to reduce coherent contour bands without
animated sampling noise or doubling light marches. We do **not** label those as eight/twelve total
density evaluations. Short/occluded/opaque segments can exit before using the budget.

| Preset | Visible intervals: vertical / cap | Actual view density evaluations: vertical / cap | Sun / sky per occupied interval | Ground shadow: vertical / cap | Extra scatter orders | Fine erosion |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| Low | 8 / 24 | 16 / 48 | 2 / 1 | 4 / 12 | 0 | Both fine bands filtered to their mean |
| Medium | 12 / 32 | 24 / 64 | 2 / 1 | 5 / 16 | 1 | 6-block Worley, filtered 3-block band |
| High | 16 / 40 | 16 / 40 | 4 / 2 | 6 / 20 | 1 | Full 6/3-block erosion |
| Very High | 20 / 48 | 20 / 48 | 5 / 2 | 7 / 24 | 2 | Full |
| Ultra | 24 / 64 | 24 / 64 | 6 / 3 | 8 / 28 | 2 | Full |

The density-only counter is not a standalone quality ranking: Medium's extra cheap quadrature probes
smooth extinction while High spends more on finer density, self-shadowing and sky illumination.
**Total executed work increases through all five presets**, including diffuse and shadow queries.

Diffuse indirect paths retain a separate cheaper tier, controlled by the same preset:

| Preset | View steps / cap | Sun samples | Sky samples | Density probes per step |
| --- | ---: | ---: | ---: | ---: |
| Low | 3 / 4 | 1 | 0 | 1 |
| Medium | 4 / 6 | 1 | 0 | 1 |
| High | 5 / 8 | 2 | 1 | 1 |
| Very High | 6 / 10 | 2 | 1 | 1 |
| Ultra | 6 / 12 | 2 | 2 | 1 |

Zero sky samples means the previous cheap local optical-column estimate, **not zero attenuation**.
Camera, specular/reflected/refracted rays and the recovered water-interface prefix use the visible
tier. Terrain and fog visibility share the shadow budget. Classic's analytic boxes, missing-map
fallback, flat legacy path and lighting are exempt; they are checked against pre-change outputs.

### Why Low actually costs less

- No 6-block Worley evaluation or 3-block Perlin evaluation in the fine-erosion stage; the mean
  erosion envelope is retained so removing detail does not inflate all cloud silhouettes.
- Same 3D warp, 12-block main lobes, coverage and thickness field in every preset. High through Ultra
  evaluate exactly the same density; only the integration/lighting budgets change.
- Much smaller **hard** view and light caps; long paths cannot silently restore Ultra's budget.
- No extra multiple-scattering phase lobes or exponentials. Phase functions are outside the loop.
- Fewer, coarser-detail ground-shadow queries; opaque scene visibility still short-circuits them.
- Empty volume/shape and saturated transmission exit early. Zero incident sun skips sun probes;
  completely unlit rays integrate extinction only, skipping both light/sky marches and scatter.

An initial Low cap of 12 visible intervals showed strong contour bands in CPU diagnostics. Increasing
that long-path cap to 24, adding two-probe extinction quadrature, and replacing two-point light strata
reduced them while keeping expensive lighting sparse. An initial two-sample ground shadow could miss
whole banks (errors approaching 1 in transmission); bounded uniform 4–12 sampling corrected that.
Remaining contouring/aliasing risks at Low and the far horizon are still part of in-game acceptance,
not something these diagnostics can certify away.

### Temporal decision

No temporal cloud buffer, animated jitter or new history has been added. `svgf_reproject.comp` bypasses
sky accumulation; `nrd_combine.comp` also passes sky through raw. Vendor DLSS behaviour cannot be
assumed to provide cloud-specific motion/history. The marcher therefore uses deterministic intervals
with a shortened last interval: changing step count starts a new interval at zero weight instead of
moving every existing midpoint. This is checked across budget boundaries, frame indices and wind.

## Executable validation

```sh
# Slang + g++ (with OpenMP), Python 3 + NumPy. Pillow only for --render.
python3 -m venv build/cloud-check-env
build/cloud-check-env/bin/python -m pip install numpy==2.4.6 Pillow==13.0.0
build/cloud-check-env/bin/python tools/validate_clouds.py --spirv --render

# Production configuration, fresh-JVM TOML round trips, UI/push contracts, existing renderer tests.
./gradlew --no-daemon test
./gradlew --no-daemon -p buildSrc test
./gradlew --no-daemon check verifyMinecraftAbi verifyModJar
```

`tools/clouds/probe.slang` includes the **production shader**, and Slang compiles it to C++, then g++
compiles/executes it as a host library. There is no copied Python noise/profile algorithm to drift from
the implementation. CPU compilation ignores GPU-only capability annotations (std430 device pointers),
not shader code; CPU structure layout is **not** used as proof of GPU ABI correctness. The separate
SPIR-V compilation uses the production flags, never ignores capabilities, covers all seven world RT
stages **and the SER variant**, and invokes `spirv-val` when available. Normal Gradle CI also validates
all shaders and generates Java records from reflection.

The original numerical tests cover all 28 ordered endpoint pairs, constant bounds, strict local/global
support, 3D morphology, periodicity, lattice boundaries, frame/order independence, wind advection,
camera rebasing, weather monotonicity, ray clipping, secondary/inside/parallel rays, finite scatter,
opacity ceilings, optical-depth convergence, both quality tiers, dimension/off gates, and classic
bitmap/fallback paths. `RtCloudConfigTest` additionally starts fresh JVMs with the **real** config and
TOML parser to test restart persistence, old/invalid files, property overrides/reload and resets.
The expanded suite has **20 tests**. It additionally checks all five presets, all 28 thickness pairs
at every preset, actual executed work, all sample caps, budget-boundary continuity, low-sun shadow
convergence, depth-dependent grey shading, zero-light culling, and 192 classic reference rays per
preset (960 comparisons). `tools/clouds/classic-reference.json` was captured from the actual shader
at `c74ec2a` before these changes, not generated from the new renderer.

The work profiler instruments a **separate generated C++ library**, counts executed density/noise,
phase, exponential, view and sky evaluations per invocation, and checks its colour/transmission
against the untouched probe. Counters are thread-local; no counters/atomics enter production Slang
or SPIR-V. Instrumentation can inhibit CSE: these are **source-level evaluation counts**, not GPU
instruction counts, milliseconds or FPS. The GPU-less CI publishes diagnostics/metrics with reports.

### Measured quality/shading follow-up

128 fixed, full-overcast vertical rays; averages include empty samples and early-outs:

| Preset | View density evaluations | Total density evaluations (view + sun + sky) | Worley calls | Extra HG work |
| --- | ---: | ---: | ---: | --- |
| Low | 16 | 32.80 | 28.38 | 0 extra lobes |
| Medium | 24 | 48.70 | 73.77 | 1 extra lobe/ray |
| High | 16 | 78.91 | 126.46 | 1 extra lobe/ray |
| Very High | 20 | 111.93 | 180.09 | 2 extra lobes/ray |
| Ultra | 24 | 165.19 | 274.52 | 2 extra lobes/ray |

- Low performs about 80% fewer **density evaluations in this test**, not 80% less GPU time. The
  expensive worst-case caps are also bounded: Low up to `24*(2+2+1)=120` such evaluations per visible
  segment, Ultra `64*(1+6+3)=640`. This excludes separate receiver shadow queries and does not count
  compiler optimisations. No RTX 2060/GPU timing was available.
- Low density correlation to Ultra is .99913 with .9757 sampled-support overlap; Medium .99996/.9945.
  High/Very High/Ultra density samples are identical. The thickness field is identical across all five.
- Against 192-interval references at sun Y = 1/.5/.1, Low view T mean errors are .00195/.00156/.00090;
  Ultra .00050/.00058/.00048. Low shadow T mean errors .0195/.0219/.0088, 95th percentiles
  .0619/.0800/.0387. The worst Low shadow error is still .357 on a thin edge, versus Ultra .062;
  this is a disclosed precision trade-off, not claimed perfect shadows.
- At the tested thin cloud bases, the old local sky-depth estimate averages .060 while the integrated
  Ultra sky depth averages 1.306. Paired material-bearing columns have mean bottom/upper radiance
  .240/.622 under neutral test illumination: the darker bases follow actual occluding material.
- Monotonic attenuation, neutral tint, finite values, no fictitious emission, unchanged opacity caps,
  camera/frame/wind invariance and sampling-budget continuity pass at every preset.
- CPU images for all five presets plus above/below/inside views were generated and inspected, with
  fixes rerun. They are synthetic-sky diagnostics, **not Minecraft screenshots or proof of perfect
  appearance**. In-game temporal behaviour and actual performance remain unmeasured.

### Initial-overhaul measurements (2026-10-19, before quality follow-up)

Executed and iterated, using Slang **2026.12** from the SlangPy distribution (the CI fallback pins
2026.18), g++ 12 and SPIRV-Tools **2026.4** built locally. Generated binaries/images stay under
ignored `build/cloud-validation/`; the downloadable toolchain stays outside tracked source.

- All 28 parameter pairs remain in bounds; all seven equal pairs are exact constants.
- Thousands of boundary/outside queries return exactly zero, including just beyond **local** tops.
- Fixed-22 morphology scan: 192×192 blocks, 2-block horizontal / .5-block vertical spacing.
  Nonempty footprint 59.3%, density standard deviation .209, top-height standard deviation 3.51
  blocks; highest nonzero sample 21.25, largest sampled visible column span 21.0 blocks.
  Edge fragments may occupy only one sample; they are not misreported as 16-block columns.
- Period shifts ±24576/±49152 on exactly representable positions: **bit-identical** density, thickness,
  coverage and extinction. Arbitrary non-dyadic float32 inputs incur up to ~.00201 density / .00159
  blocks thickness difference from rounding the *input position* near 73k blocks. Tests record this
  separately instead of claiming bit identity for already-rounded, different coordinates.
- Across lattice/wrap boundaries and the inside-camera crossing, differences converge toward zero
  as the sampling gap shrinks. The last inside-ray gap (.005 blocks) changes T by at most .00253.
- Against a 192-interval vertical optical-depth reference: full eye T mean/max error .00064/.00457;
  six-sample light T mean/max error .0191/.1315. The latter is a real approximation limit, not hidden
  by a looser opacity ceiling. Side-light/grazing errors and temporal aliasing still need GPU review.
- Eight production RT SPIR-V variants compiled and passed Vulkan 1.2 validation locally.
- Local Gradle execution was attempted but the wrapper download from `services.gradle.org` is
  blocked by the sandbox's network policy. The PR CI is the full JVM/ABI/JAR validation route.

CPU render diagnostics were generated and inspected from below, above, inside/side, and as a density
cross-section. They caught the initial flat-topped crown and insufficient envelope deformation; both
were corrected and the suite rerun. These images use a simple synthetic sky, not Minecraft's
atmosphere/terrain/denoisers. **They do not establish photographic realism.**

## Remaining in-game acceptance checklist

Use the same seed, coordinates, resolution/exposure and time when comparing against the old shader.

- All five presets with 16/16, 16/22, 19/19, 22/22; drag endpoints across each other, reopen, reset and
  restart. Check English/Portuguese labels, old config defaults and classic-style exemption.
- Below, above, inside, nearly parallel and terrain-clipped rays; camera-origin rebases, distant world
  coordinates and wind wrap on both axes. Watch the old camera-following horizontal band specifically.
- Noon, low sun/backlight, sunset, moonlight, rain and thunder. Inspect dense-core shading, rim detail,
  silver lining, terrain/cloud shadow alignment and scene-dependent atmosphere tint.
- Water/glass reflections and refractions; DLSS-RR/NRD/SVGF and raw output. Check shimmer, ghosting,
  coarse cheap-tier indirect light, distant details and the finite horizon march.
- Classic resource packs (normal/missing clouds.png), original thickness values, rain fill and colour.
- RTX 2060 GPU frame time at each preset, fixed camera/weather/exposure, several SPP/path depths,
  fog and reflections on/off. Watch Low/Medium contour bands, moving silhouettes and shadow edges
  in raw, SVGF, NRD and DLSS-RR output. Do not infer FPS from the CPU evaluation counters.
- No Vulkan RT device or running Minecraft client was available in the implementation sandbox.
