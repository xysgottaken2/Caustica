# Porting Caustica from Minecraft 26.2 to 26.3

This repository now carries Caustica's renderer, imported from
[`xysgottaken2/testingcasutica`](https://github.com/xysgottaken2/testingcasutica). The upstream
`main` branch targets **Minecraft 26.2**; 26.3 is an API-breaking release that cannot consume a 26.2
Mixin set. This document records what the import changed, what was verified, and what remains
unproven.

## Provenance

- Renderer source: `testingcasutica` at
  `arena/01a0c1c8-testingcasutica` — the furthest 26.3-ported state in that repository. Its five
  26.3 commits (`26.3: dependency versions + mechanical renderpearl re-package`,
  `26.3: Vulkan device negotiation via FeatureSet`, `26.3: frame graph + UI overlay port`,
  `26.3: entity/terrain submits + composite vectors + mixin config`,
  `26.3: re-resolve overlay composite pipeline per frame`) are included here.
- The temporary CI failure-log capture commit from that branch is **not** included.
- Build plumbing, the Minecraft ABI contract and the hook call-site contracts come from this
  repository's previous work and were retargeted to the Caustica artifacts.

## The 26.2 → 26.3 break, and how each item is handled

| 26.2 (upstream Caustica) | 26.3 (this repository) | Handling |
|---|---|---|
| `com.mojang.blaze3d.vulkan.*` | `com.mojang.renderpearl.backend.vulkan.*` | Mechanical re-package across every source, shader binding comment and mixin owner |
| `blaze3d.systems.GpuDevice` (class holding a backend) | `renderpearl.api.device.GpuDevice` (interface), implementation in `renderpearl.frontend.FrontendGpuDevice` | `GpuDeviceAccessor` mixes into the concrete frontend class |
| `createDevice(long, ShaderSource, GpuDebugOptions, Runnable)` | `createDevice(GpuDebugOptions)` | Old descriptor no longer exists; the hook now wraps the new signature |
| `createDevice(Collection, VulkanPhysicalDevice, Set)` | `createDevice(FeatureSet, VulkanPhysicalDevice)` | Negotiation goes through named feature sets (`VulkanFeatureSets`, `FeatureSet.checkCondition`) |
| `VulkanPNextStruct(int sType, int offset)` | `VulkanPNextStruct(Class<?> struct)` / `(Class, int, int)` | Offsets are no longer hand-carried; `VulkanFeature(struct, name)` resolves the member itself |
| GLFW window hints (`GLX`, `glfwVulkanSupported`) | SDL3 (`lwjgl-sdl`), `RenderSystem.initBackendSystem` | `GlxMixin` was deleted; `RenderSystemMixin` was added to pin the Wayland video driver for HDR |
| `GameRenderer.render(DeltaTracker, boolean)` and `renderLevel(DeltaTracker)` | `extract(DeltaTracker, boolean)` / `render()` and `renderLevel()` | The frame-graph hook was rewritten around the extract/render split |
| `LevelRenderer.render(..., DeltaTracker, ..., Matrix4fc, ...)` | Arguments dropped, `consistentDepthRequired` added | `LevelRendererMixin` re-targeted |
| Entity/terrain submission on the old render path | Extracted render states + submit steps | `RtEntityCollector`/`RtTerrain` re-targeted onto the 26.3 submission points |
| Shader cache/compiler inside `VulkanDevice` | `FrontendGpuDevice` + `frontend.shaders.GlslCompiler` | Unchanged: this project compiles its own SPIR-V at build time |
| Loom 1.17-SNAPSHOT + `fabric.loom.disableObfuscation` | Loom **1.18.2** with the `net.fabricmc.fabric-loom` no-remap marker plugin | 26.3 is distributed with official names, so no mappings/remapping step exists |
| Minecraft 26.2 / Loader 0.19.3 / Fabric API 0.153.0+26.2 | Minecraft **26.3** / Loader **0.19.5** / Fabric API **0.161.0+26.3** | Pinned in `gradle.properties`; `clientOnlyMinecraftJar()` keeps RenderPearl in the resolved client JAR |

## Breakage that only the compiler could find

The static cross-check above resolves types and descriptors, but three 26.2 call sites were still
wrong against the resolved 26.3 dependency set. All three were found by the first CI compile and are
fixed:

- `RtContext` explicitly aligned buffers: LWJGL's VMA binding has **no** `vmaCreateBufferWithAlignment`
  (the alignment overload only exists in the C API). Aligned buffers now request a
  `VMA_ALLOCATION_CREATE_DEDICATED_MEMORY_BIT` block — dedicated memory is page-aligned, which covers
  the acceleration-structure scratch and SBT strides — and the returned device address is still
  verified, so a driver that returns something coarser fails loudly instead of corrupting the SBT.
- `VulkanDiagnostics`: in LWJGL 3.4.3 the `VkDeviceFault*EXT` record structs are subclass aliases of
  the `VkDeviceFault*KHR` ones (and `VkDeviceFaultCountsKHR` does not exist), so reading the EXT
  buffers through their accessors no longer converts. The fault address/vendor records are now
  declared as `...KHR` while the `VK_EXT_device_fault` entry point and feature struct are kept.
- `RtNameTagFeature.GlyphCapture`: `VertexConsumer` gained the abstract `setUv3(float, float)` in
  26.3 (the `UV3` vertex semantic); the glyph capture ignores it like the other capture paths do.

The packaging job also had to learn that `bundle*Natives` is never up to date and rewrites its
native roots, so `jar` is rebuilt whenever those properties change: `verifyModJar
-PexpectBundledNatives=...` now runs in the *same* Gradle invocation as the build. Re-invoking
Gradle without `-PngxPlatforms`/`DLSS_SDK` silently re-bundled for the runner's own platform and
produced a JAR without the Windows natives.

## CI evidence

Run [`35734998325`](https://github.com/xysgottaken2/test-3/actions/runs/35734998325) (commit
`58649c7`) and the pull-request run `35735004183` are green on all four jobs:

- *JVM, ABI and JAR checks (no GPU)* — buildSrc contract tests, `compileJava` against 26.3, the
  renderer unit-test suite (24 classes, 123 tests), `verifyMinecraftAbi`
  ("155 member signatures verified"), `jar verifyModJar`.
- *Build Windows shims (NGX, FSR, NRD, XeSS)* and *Build Linux NGX shim* — the shim libraries.
- *Build bundled mod JAR* — rebuilds the JAR with those artifacts and asserts the requested natives
  (`windows-x64`: NGX shim + `nvngx_dlssd`/`nvngx_dlssg`, FSR shim + `amd_fidelityfx_vk`, NRD and
  XeSS shims; `linux-x64`: the NGX shim and vendor `.so`s) are present.

Both runs predate the final documentation change in this file, which does not affect any build input.

## Preserved from this repository

The previous bootstrap was replaced, but its verification work was kept and retargeted:

- `buildSrc/.../AbiContract.groovy` + `VerifyMinecraftAbi.groovy` and
  `docs/minecraft-26.3-abi.tsv` (155 member signatures, checked against resolved classfiles).
- `TerrainHookContract.groovy` / `EntityHookContract.groovy`: bytecode call-site guards for the
  terrain extraction/upload ordering and the entity quad-upload emission points the hooks depend on,
  with their `buildSrc` unit tests.
- `verifyModJar`: packaged metadata, registered mixins, compiled shaders, and — in the bundled-JAR
  job — the presence of every requested native platform.
- Reproducible archives, Java 25 toolchain and the pinned GitHub Action SHAs in CI.

The previous bootstrap's own TLAS/BLAS implementation (`AccelerationStructureManager`,
`RayTracingPipeline`, `WorldGeometryManager`) was **not** carried over: the imported renderer owns
the acceleration-structure stack (`rt/accel/RtAccel`, `rt/pipeline/RtPipeline`) and already covers
more of it. The regression guards those classes had (descriptor-size handling in LWJGL 3.4.3 and SBT
alignment) were reviewed against the imported code instead of being copied blindly.

## Verification performed

Without a JDK, a GPU or network access to the Maven/Gradle infrastructure in the authoring
environment, correctness was established by cross-checking the port against the real 26.3 sources
(`mc-dataminning/build-changes`, tag `26.3`, the same decompilation used for the ABI contract):

- Every `com.mojang.*` / `net.minecraft.*` type referenced by the mod resolves in the 26.3 tree.
- All 25 mixins' target classes, injection points, `@Accessor` fields and `@Invoker` methods exist
  with matching descriptors in 26.3.
- Calls made on Minecraft/RenderPearl typed receivers (including one level of supertype traversal)
  resolve against the 26.3 declarations.

What that does **not** cover, and therefore what CI must not be read as proving: runtime
applicability of any Mixin, whether a hook is reached at the right moment, GPU/SPIR-V behaviour at
dispatch time, vendor SDK interaction (DLSS/FSR/XeSS/NRD), and anything visual. Those require a
Vulkan ray-tracing GPU and a real client session.
