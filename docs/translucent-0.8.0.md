# 0.8.0 — TRANSLUCENT terrain (experimental, awaiting in-game validation)

## Baseline and source investigation

The user validated 0.7.0 in game: SOLID, original UV/texture/tint, Grass Block
coplanar overlay, CUTOUT/alpha-test, leaves/grass/flowers/holes. These are the
accepted baseline, not outstanding geometry tests. New TRANSLUCENT runtime
behavior is **not yet validated**. No local Java or Vulkan GPU is available.

Research: `mc-dataminning/build-changes`, branch `26.3`, commit
`213a1038d61f60468efdfd73c34138cd5b2679ce`. This is decompiled third-party source;
CI verifies the accessed signatures and test contracts against resolved 26.3
classes, not against assumptions about earlier Minecraft releases.

* `FaceBakery` / baked material → `SectionCompiler` / layer builder →
  `MeshData` → `CompiledSectionMesh` → accepted `RenderSection` →
  `LevelRenderer.extractSectionDrawGroups` → dispatcher layer-specific
  `RenderSectionBufferSlice` / vertex AND index `UberGpuBuffer` allocations.
* `ChunkSectionLayerGroup.TRANSLUCENT` contains TRANSLUCENT. OPAQUE still contains
  SOLID+CUTOUT. Direct and MDI extraction enumerate the same actual layers.
* `TRANSLUCENT_TERRAIN[_MULTIDRAW]` inherits BLOCK28/QUADS from
  GENERIC_BLOCKS → LIT_BLOCKS → TERRAIN/MULTIDRAW_TERRAIN snippets. Position:
  RGB32_FLOAT byte0; Color: RGBA8_UNORM byte12; UV0: RG32_FLOAT byte16;
  UV2: RG16_SINT byte24. MDI's binding1 CHUNK_DATA_INSTANCED is separate instance
  metadata, not a change to binding0's section vertices.
* Classic pipelines: `ALPHA_CUTOUT=0.1`, culling, SRC_ALPHA / ONE_MINUS_SRC_ALPHA
  RGB blend; ONE / ONE_MINUS_SRC_ALPHA alpha blend. This is NOT CUTOUT's .5.
* `LevelRenderer` selects classic transparency or `executeOit` through
  `useImprovedTransparency`. OIT_TERRAIN[_MULTIDRAW] uses DEPTH_BOUNDS,
  TRANSMITTANCE, ACCUMULATE; the same terrain layer, .1 cutoff and original
  vertices/indices. `oit_sample.glsl` estimates transmittance using wavelet
  coefficients, accumulates premultiplied color; OIT_COMPOSITE resolves it.
  RT implements ordered surface source-over, **not** a copy of that raster OIT
  approximation or its auxiliary render targets.
* `SectionCompiler` calls `MeshData.sortQuads` for TRANSLUCENT.
  `SortState.writeIndices` permutes complete quads; inside each quad the triangles
  remain 0,1,2 / 2,3,0. `ResortTransparencyTask` replaces only the indices on the
  same compiled mesh. Vertex bytes are not reordered. SHORT and INT are real
  supported index formats. Uber heaps add COPY_SRC and COPY_DST usage.
* `FluidRenderer` obtains `FluidModel`, writes through `output.getBuilder(model.layer())`,
  uses actual tintSource and cardinal shading in Color, original per-corner
  heights, still/flow/overlay UVs and optional reversed faces. FluidModel baking
  determines its layer from sprite transparency/forceTranslucent. No water
  geometry, tint or fixed opacity is invented in RT.
* `assets/minecraft/models/block/glass.json` explicitly forces TRANSLUCENT.
  Texture alpha still controls which glass regions pass .1 and which blend/stop.
  Other blocks/fluids are included by their actual compiled layer, not name lists.
  Original prepared Color incorporates per-face tint/shading; no tintIndex or
  sprite-name field exists in BLOCK28. UV2 is preserved; full lightmap/fog/lighting
  remains outside this milestone, as in the accepted baseline.

## GPU path, cache and lifetime

TRANSLUCENT has independent residents/BLAS alongside SOLID and CUTOUT, in the
same TLAS and material-table order: SOLID → CUTOUT → TRANSLUCENT. Its non-opaque
geometry invokes the existing any-hit stage. The hit group and three SBT records
are unchanged. The material row grows 80→96 bytes with original index address,
width and count; preexisting fields/flags keep their offsets. Both hit shaders
read the same original index snapshot, including packed SHORT without shaderInt16.

Only verified vanilla CompiledSectionMesh with matching SortState quad count/type
is accepted. Vertex/index upload state, source handles, copy usage, bounds,
alignment, accepted mesh/owner and exact captured slices are checked. New geometry
is revalidated and copied under the original dispatcher lock, before vanilla
allocation mutations; existing transfer/AS/trace barriers and deferred retirement
remain. Both owned GPU copies outlive their BLAS. No GPU→CPU readback, CPU block
retessellation, copied/reduced atlas, new backend/device, or parallel renderer.

Mesh/vertex allocation changes replace the relevant BLAS. Camera-only index
re-sorting does not: the frozen copied indices still describe precisely the same
triangles with original winding/UV/Color. Primitive IDs describe that snapshot,
not the latest raster sort order. Stable scenes reuse all three layer caches.

Closest-hit returns sampled color, alpha and distance. Raygen accumulates
`C += T * alpha * RGB; T *= 1-alpha`, then traces the next closest surface.
Any-hit ONLY culls/rejects low alpha: .5 for CUTOUT, .1 for TRANSLUCENT. It never
blends candidates in traversal order. SOLID/CUTOUT remain terminating opaque
surfaces after their existing shading/alpha-test. TEXEL at the original atlas
view's mip0 remains the default/reference, with no atlas copy or enlargement.

Continuation uses the same origin/direction and the next positive-float ULP of
absolute hit distance; any-hit also rejects the previously accepted triangle.
This avoids a large fixed bias that could skip thin glass. Work is bounded at
64 accepted translucent surfaces, with an explicit overflow flag and one final
SOLID/CUTOUT-only background ray (instance mask1 excludes TRANSLUCENT mask2).
Residual weight is at most .9^64 ≈ .00118 because accepted alpha is ≥.1.
No recursion/SBT redesign or runtime shader compilation is introduced.

Limits: no refraction, shadows/reflections/GI, skybox, entities/falling blocks/
particles; the existing dark miss color supplies missing background. Exact
coincident translucent surfaces at the same representable ray distance cannot
be independently depth-ordered by this continuation method. It is basic surface
alpha composition, not underwater fog, volumetric water or raster OIT equivalence.

## Evidence and optional diagnostics

Normal use: replace JAR and open the world. No complex commands are required.
Keep `-Dnativevulkanrt.enabled=true -Dnativevulkanrt.scene=chunks
-Dnativevulkanrt.textureSampling=texel`.

Logs separately report capture acceptance/rejection, original indexed slice,
TRANSLUCENT sections/triangles/vertexBytes, builtSinceReport, retiredSinceReport
(deferred), reusedThisFrame, plus preserved SOLID/CUTOUT and total AS counts.
These are CPU committed/enqueued observations, NOT proof of a GPU hit.

With `-Dnativevulkanrt.materialDiagnostics=true`, the GPU-only center-ray probe
(272 bytes; no readback) preserves existing fields and adds:

* `LAYER=TRANS`: first nearest accepted surface, not last traversal candidate.
* `TTEST`, `TDROP`: TRANSLUCENT any-hit texture-alpha tests/rejections across
  traces; counts are not unique surfaces and can exceed composed layers.
* `TCOMPOSE`: accepted nearest translucent surfaces actually composed.
* `TA TEX,VERT`, `TA FINAL,REMAIN`: texture/vertex/product alpha and remaining
  transmittance, displayed 0–255. Partial final alpha plus composition/continuation
  distinguishes transparency from opaque or CUTOUT-only treatment.
* `TRN UV`, `TSECTION`, `TPRIM`: original UV and section/snapshot primitive.
* `TSELECT=1`: those sample fields identify the first closest composed surface.
  `TSELECT=0`: last tested candidate, which may have been discarded or hidden;
  absence is shown by blank sample fields. This is not a claimed visible hit.
* `TOVERFLOW,END`: overflow0/1; END0 miss background,1 SOLID/CUTOUT background,
  2 fully opaque translucent surface,3 maximum ray distance. COMPOSE>0 with
  partial alpha and END0/1 is executed continuation, not CPU enqueue telemetry.

Optional `-Dnativevulkanrt.tintProbeBlock=x,y,z`: bounded CPU logs link actual
block/model layer/sprite/tint to prepared block quads. New FluidRenderer hooks
log emitted position/UV/Color alpha/light and UV-containing fluid sprite
candidates. Compilation-thread-local context; maximum64 observations; no geometry
modification. Sprite candidates/metadata are not read-back texture pixels.

## Acceptance still required for this new layer

Use glass and preferably colored glass with solid blocks behind, water above
visible substrate, and another material actually classified TRANSLUCENT. Check:
actual capture and BLAS/TLAS presence; correct UV/Color and variable alpha;
background/substrate visible through fractional-alpha regions; overlapping
transparent surfaces ordered sensibly; unchanged SOLID, overlay and CUTOUT;
then stable layer caches after loading and camera sorting. Also change/remove
blocks, reload resources/world and cross section boundaries.

Glass merely appearing is insufficient. CI's CPU/ABI/SPIR-V tests cannot replace
these visual checks. Build commands: `./gradlew --no-daemon check assemble
verifyMinecraftAbi verifyModJar`; independent Ubuntu/Windows reports and the
actual `build/libs/native-vulkan-rt-0.8.0-experimental.jar` are published by CI.
Local attempt still stops at `JAVA_HOME is not set and no 'java' command could be
found in your PATH.` Current publication status/links are recorded in PR #1.
