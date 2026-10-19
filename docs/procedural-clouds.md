# Procedural volumetric clouds: geometry, integration and validation

## Investigation and scope

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
  record; the already-full 128-byte hardware push-constant block is untouched.
- Old `cloud-thickness` is preserved and still controls classic's 4–110-block boxes. It no longer
  scales the volumetric envelope. The old default was 55 nominal blocks, outside the requested range;
  16/22 uses the largest permitted upper bound and immediately enables natural regional variation.
  Old files without the new keys get these defaults; switching styles does not destroy old settings.

## Lighting and marching

A common `cloudOpticalDensity` now supplies eye extinction, light-marched self-shadow and volumetric
ground shadows, normalised by **local**, not maximum, thickness. The previous light march used a
different coefficient and the ground shadow sampled an unscaled footprint. Opacity remains a final
ceiling, so a 25% slider can never become more than 25% opaque at grazing angles.

The light march uses actual slab entry/exit and quadratically spaced integration intervals, retaining
nearby blockers. The dual HG lobe remains; two weaker, more isotropic orders reuse optical depth to
approximate multiple scattering. A directional optical-depth powder term and a depth-attenuated sky
fill replace the uniform bright interior. This is an approximation, **not a full multiple-scattering
solver**. Celestial radiance/sky still come from the existing day/night, atmosphere and weather paths.

Slab bounds now remain camera-relative throughout sampling. Previously they had the secondary ray's
origin Y subtracted and were then compared with camera-relative samples, effectively shifting the
profile twice. Real volumetric clouds also no longer dissolve at the camera's deck-centre crossing.
Inside and parallel rays are handled geometrically. Early termination keeps the residual transmission
instead of snapping the old 1% tail to zero.

Full/cheap tiers remain (24/6 base samples, 6/2 light samples). Full marching targets 1.5-block steps,
up to 64, to resolve the new 3-block detail; cheap caps at 12. The established 3.5-slab-depth integration
limit and distance fade remain. **This can omit distant banks on extremely grazing rays.** The new
3D field costs more ALU than the old 2D field, and volumetric ground shadows now perform six density
samples. Empty density/shape regions skip fine detail and lighting, but no GPU timing or FPS
improvement is claimed. Profile SPP, reflections and fog together on actual hardware.

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

The 11 numerical tests cover all 28 ordered endpoint pairs, constant bounds, strict local/global
support, 3D morphology, periodicity, lattice boundaries, frame/order independence, wind advection,
camera rebasing, weather monotonicity, ray clipping, secondary/inside/parallel rays, finite scatter,
opacity ceilings, optical-depth convergence, both quality tiers, dimension/off gates, and classic
bitmap/fallback paths. `RtCloudConfigTest` additionally starts fresh JVMs with the **real** config and
TOML parser to test restart persistence, old/invalid files, property overrides/reload and resets.
The GPU-less CI lane runs the CPU suite and publishes diagnostic PNGs/metrics with its test reports.

### Measured locally (2026-10-19)

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

- 16/16, 16/22, 19/19, 22/22; drag endpoints across each other, reopen, reset and restart.
- Below, above, inside, nearly parallel and terrain-clipped rays; camera-origin rebases, distant world
  coordinates and wind wrap on both axes. Watch the old camera-following horizontal band specifically.
- Noon, low sun/backlight, sunset, moonlight, rain and thunder. Inspect dense-core shading, rim detail,
  silver lining, terrain/cloud shadow alignment and scene-dependent atmosphere tint.
- Water/glass reflections and refractions; DLSS-RR/NRD/SVGF and raw output. Check shimmer, ghosting,
  coarse cheap-tier indirect light, distant details and the finite horizon march.
- Classic resource packs (normal/missing clouds.png), original thickness values, rain fill and colour.
- GPU frame time at multiple SPP/path depths with fog enabled. No Vulkan RT device or running
  Minecraft client was available in the implementation sandbox.
