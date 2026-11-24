# Developer Guide

## Windows

1. Install the Vulkan SDK from <https://vulkan.lunarg.com/sdk/home>.
   The installer sets `VULKAN_SDK` automatically.
2. Download the DLSS SDK from <https://github.com/NVIDIA/DLSS/releases>.
   Extract it, then set `DLSS_SDK` to the folder you extracted.

   To set it permanently for your Windows user account, run PowerShell with:

   ```powershell
   [Environment]::SetEnvironmentVariable("DLSS_SDK", "C:\path\to\dlss-sdk", "User")
   ```

   Restart your terminal after setting it. To set it only for the current
   PowerShell session, use:

   ```powershell
   $env:DLSS_SDK = "C:\path\to\dlss-sdk"
   ```

3. Configure and build the native shim:

```powershell
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release --config Release
```

4. (Optional, FSR 3 upscaler) Download the AMD FidelityFX SDK from
   <https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/releases>
   (v1.1.4+) and set `FFX_SDK` to the checkout folder:

   ```powershell
   [Environment]::SetEnvironmentVariable("FFX_SDK", "C:\path\to\FidelityFX-SDK", "User")
   ```

   Then configure and build the FSR shim (links AMD's signed prebuilt
   `amd_fidelityfx_vk.dll` and copies it next to the shim; both are bundled by
   gradle afterwards). Windows only for now:

   ```powershell
   cmake -S native/fsr_shim -B build/cmake/fsr_shim/release -DCMAKE_BUILD_TYPE=Release
   cmake --build build/cmake/fsr_shim/release --config Release
   ```

   Without the shim the mod still builds and runs; the upscaler selector just
   does not offer FSR 3.

5. (Optional, NRD denoiser) Clone NVIDIA NRD and point `NRD_SDK` at it. The
   shim builds NRD (and its NRI dependency, fetched automatically) as a static
   library with embedded SPIR-V, then wraps it behind the flat C ABI:

   ```powershell
   git clone --branch v4.17.3 https://github.com/NVIDIA-RTX/NRD C:\path\to\NRD
   [Environment]::SetEnvironmentVariable("NRD_SDK", "C:\path\to\NRD", "User")
   ```

   Then configure and build (Windows only for now):

   ```powershell
   cmake -S native/nrd_shim -B build/cmake/nrd_shim/release -DCMAKE_BUILD_TYPE=Release
   cmake --build build/cmake/nrd_shim/release --config Release
   ```

   Without the shim the NRD toggle simply stays hidden.

6. (Optional, XeSS upscaler) Clone Intel's XeSS SDK (only `bin/` + `inc/` are
   used) and point `XESS_SDK` at it. The shim loads Intel's prebuilt
   `libxess.dll` at run time and copies it next to the shim; both are bundled
   by gradle afterwards. Windows only:

   ```powershell
   git clone --branch v2.1.1 https://github.com/intel/xess C:\path\to\xess
   [Environment]::SetEnvironmentVariable("XESS_SDK", "C:\path\to\xess", "User")
   ```

   Then configure and build:

   ```powershell
   cmake -S native/xess_shim -B build/cmake/xess_shim/release -DCMAKE_BUILD_TYPE=Release
   cmake --build build/cmake/xess_shim/release --config Release
   ```

   Without the shim the mod still builds and runs; the upscaler selector just
   does not offer XeSS.

7. Run the client:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC"
.\gradlew.bat runClient --args="--renderDebugLabels --graphicsBackend VULKAN"
```

## Linux

Set `DLSS_SDK` and `VULKAN_SDK` before configuring CMake:

```bash
export DLSS_SDK=/path/to/dlss-sdk
export VULKAN_SDK=/path/to/vulkan-sdk
```

`DLSS_SDK` must contain the NGX headers and static library. `VULKAN_SDK` must
contain Vulkan headers.

The FSR 3 shim is Windows-only for now (the FidelityFX SDK ships prebuilt
Vulkan runtimes for Windows; a Linux build would compile the ffx-api backend
from source), so `bundleFsrNatives` simply bundles nothing here.

Then configure and build the native shim:

```bash
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

On NixOS, enter the development shell from `flake.nix` instead of setting up
the toolchain by hand:

```bash
nix develop
cmake -S native/ngx_shim -B build/cmake/ngx_shim/release -DCMAKE_BUILD_TYPE=Release
cmake --build build/cmake/ngx_shim/release
```

## Reconstruction Slots

One reconstruction pass runs per frame, in this priority order:

| Slot | Backend | Runs when |
| --- | --- | --- |
| 1 | `RtDlssRr` (denoise + upscale) | `dlss-rr.enabled` and `denoiser-mode=dlss_rr` |
| 2 | `RtDlssSr` (upscale only) | `dlss-rr.enabled` and any other `denoiser-mode` (SVGF / A-SVGF / NRD) |
| 3 | `RtFsrUpscaler` (upscale only) | `fsr.enabled` |
| 4 | `RtXessUpscaler` (upscale only) | `xess.enabled` |
| — | linear blit | none of the above (trace runs 1:1 with the display) |

The slot decides the render resolution: `RtComposite.ensureOutput` asks the active
backend for the size its quality mode expects, and the trace plus every guide buffer
is allocated at that size. `RtDlssSr` exists so that choosing one of the renderer's
own denoisers does not silently drop the trace back to native resolution — it
upscales the denoised image, and is fed zero jitter because the denoiser has already
integrated the jitter sequence.

### Inspecting the denoiser

Debug views 1–7 show the tracer's guides and therefore run with the denoiser off — they
say nothing about what the filter did. Views 10–12 and 14 are painted by the wavelet
itself and keep the cascade running, because the only reliable way to explain a
denoiser artefact is to read the state that produced it:

| View | Label | Read it as |
| --- | --- | --- |
| 10 | SVGF: History Length | black = the reprojection threw the history away at that pixel |
| 11 | SVGF: Variance | the variance driving the edge stop, log-scaled |
| 12 | SVGF: Luminance Sigma | bright = the bilateral is accepting everything (a box blur) |
| 14 | SVGF: Normal Stop | the share of neighbour weight the **normal** edge-stop discards |

View 14 exists because the normal stop can disable the filter entirely without an error
of its own: `pow(dot(n_centre, n_tap), phi_normal)` with `phi_normal = 128` collapses to
~0 for any two normals more than a few degrees apart. A guide carrying per-texel shading
detail — a LabPBR normal map, or the tangent normal a parallax side-wall hit reports —
therefore makes the cascade one-dimensional (it filters along the texture's grain only)
or inert, and the visible result is 1-spp noise standing in texel-aligned strips that
look exactly like a genuine geometric edge in the output. White in view 14 means every
tap at that pixel was rejected, so no amount of tuning the luminance stop can help: the
guide itself is the problem. 13 is SHaRC's cache overlay, not a denoiser view.

Debug view 5 is the motion-vector readout the temporal stages reproject with: brightness is
the magnitude (black at rest) and hue is the direction, so a correct field is a smooth
gradient across a surface while an incoherent one is noise. Read it as "does the motion
agree with the geometry" — a wrong field shows up as speckle at rest or as structure that
does not follow the surfaces while moving.

For the NRD path the equivalent is **NRD Validation Overlay** in the Debug page, which
replaces the frame with NRD's own 4x4 validation grid (`OUT_VALIDATION`, written by
`REBLUR_Validation.cs.hlsl`). It is the only view that can distinguish "history is being
rejected" from "the signal arriving is wrong", and it needs the upscaler off so the grid is
not filtered. The frame after any history reset is entirely black, which is normal.

Tiles are numbered row-major, top-left to bottom-right, each with its label drawn in the
top-left corner:

| # | Tile | Reading it |
|---|------|------------|
| 0 | NORMALS | World normal as RGB (`n * 0.5 + 0.5`). Should follow the surface, flat across a wall. |
| 1 | ROUGHNESS | Linear roughness as grey. |
| 2 | Z | View depth, green (positive) / blue (negative), brightness compresses with distance. Red is *outside* `denoisingRange` — the pixel is not denoised at all. |
| 3 | MV | The motion-vector validator: red is `\|mv reprojection − geometric reprojection\|` in render pixels, computed by reprojecting the reconstructed world position through `worldToClipPrev`. Blue means the reprojection left the screen. Near-black everywhere is correct (a sub-pixel residue from jitter is expected); structure *outlining the surfaces that misbehave* means the motion field is wrong there. |
| 4 | UNITS & JITTER | `frac(worldPosition)` as a lattice, top-right the jitter offset, bottom-right the rotator directions. The lattice must stick to the world while the camera moves; if it swims, positions and reprojection disagree. |
| 7 | VIRTUAL HISTORY | Specular virtual history accumulation, black on the first frames. |
| 8 | DIFF FRAMES | Colourised accumulated diffuse frame count; red is one frame (fresh), dark is converged. Red or speckled red/yellow over a *static* surface is history being discarded there. |
| 11 | SPEC FRAMES | The same for specular — the tile to read when reflections are noisy. |
| 12 | DIFF HITT | Normalised diffuse hit distance. Red is exactly zero, i.e. *no data*; magenta is outside `[0, 1]`, a contract violation. |
| 15 | SPEC HITT | The same for specular. |

Tiles 5, 6, 9, 10, 13 and 14 are unused and stay black. RELAX ships its own variant with a
different tile set, so the labels — not a remembered layout — are what to read.

Reading a grid whose labels are a few pixels tall is guesswork, and a guessed viewport sends the
investigation to the wrong tile. **NRD Validation: Single Cell** (`nrd.validationCell`) therefore
magnifies one cell to the whole frame — nearest-filtered, so individual pixels and the label stay
legible at 4x. `-1` keeps the grid; `0`-`15` pick the cell, row-major exactly as NRD indexes them
(`NrdValidationGrid` holds that mapping and its tests pin it). The usual sequence is: spot something
suspicious in the grid, isolate that cell, then read it at full size.

One caveat that bites during diagnosis: the history length is derived from a target accumulation
time, so at 15 fps the denoiser legitimately has fewer frames than at 60, and at 2 fps the frame
counts in the overlay mean very little. Judge frames-coloured tiles only once the overlay itself
runs at a playable frame rate.

### What the NRD path is fed

REBLUR's inputs match NRD v4.17.3's contract: `IN_MV` is non-jittered
(`previous - current`, in render pixels, scaled by `1/renderWidth`), `IN_VIEWZ` is positive
linear view-Z with sky above `denoisingRange`, normals are unit `xyz` in an
`RGBA16_SNORM`-declared binding with linear roughness in `w`, and the radiance pair is the
demodulated YCoCg + normalized hit distance produced by the front-end mirrors in
`world_core.slang` (`_NRD_LinearToYCoCg`, `_REBLUR_GetHitDistanceNormalization` with
A/B/C = 3/0.1/20). Keep those mirrors in lock-step with `NRD.hlsli`.

`gNormal.xyz` carries the **geometric** normal, deliberately, and this is load-bearing.
`payload.normal` is the *shading* normal — `world.rchit.slang` overwrites it with
`surface.normal` (material normal map) and then, for a parallax side-wall hit, with a
*tangent* normal. Feeding that to a denoiser breaks both filters at texel frequency:
SVGF's `pow(dot(nc, nt), 128)` is ~0 for any two normals a few degrees apart, and REBLUR
rejects on plane distance and lobe angle. The visible symptom is strips and speckle the
filter cannot remove, worst in shadows and reflections where the signal has the least
energy to spare. So the hit shader keeps a second field (`Payload.geoNormal`, captured
before the map and the wall override) and `writeGuides` publishes *that*; `gv_normal`
keeps the shading normal for the specular guides, which do want the detail. Shading
detail still reaches the eye because it is baked into the radiance the guide accompanies.

## Native Bundling

Gradle bundles NGX natives for the current host platform by default:

```bash
./gradlew build
```

Release builds that already have both platform shims available can request a
cross-platform native bundle:

```bash
./gradlew build -PngxPlatforms=windows-x64,linux-x64
```

Run the Vulkan RT/DLSS-RR client with:

```bash
JAVA_TOOL_OPTIONS='-Xmx8G -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+UseStringDeduplication -XX:+UseZGC' nvidia-offload ./gradlew runClient --args='--renderDebugLabels --graphicsBackend VULKAN'
```
