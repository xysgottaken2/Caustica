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
