# Third-Party Notices

Caustica's project-owned code is licensed under `LGPL-3.0-or-later`. This file
documents third-party components and license boundaries that are not changed by
Caustica's license.

## NVIDIA DLSS / NGX SDK

Caustica can build and distribute release artifacts that include NVIDIA DLSS/NGX
SDK runtime components, including DLSS Ray Reconstruction and Frame Generation
libraries. These NVIDIA components are proprietary third-party software and are
not licensed under the LGPL.

The NVIDIA SDK components remain subject to the NVIDIA RTX SDKs license:

<https://github.com/NVIDIA/DLSS/blob/main/LICENSE.txt>

The LGPL license grant for Caustica does not grant rights to NVIDIA SDK
components. Redistribution and use of those components must comply with
NVIDIA's license terms.

This software contains source code provided by NVIDIA Corporation.

Bundled NVIDIA SDK runtime libraries may include files matching:

- `caustica/natives/windows-x64/nvngx_dlssd.dll`
- `caustica/natives/windows-x64/nvngx_dlssg.dll`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssd.so*`
- `caustica/natives/linux-x64/libnvidia-ngx-dlssg.so*`

Caustica's `ngxshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## AMD FidelityFX SDK (FSR 3)

Caustica can build and distribute release artifacts that include AMD FidelityFX
runtime components for FSR 3 upscaling. The signed `amd_fidelityfx_vk.dll`
runtime is provided by AMD under the FidelityFX SDK license (MIT) and is not
part of Caustica's LGPL grant:

<https://github.com/GPUOpen-LibrariesAndSDKs/FidelityFX-SDK/blob/main/LICENSE.txt>

Redistribution and use of those components must comply with AMD's license
terms. Bundled AMD runtime libraries may include files matching:

- `caustica/natives/windows-x64/amd_fidelityfx_vk.dll`

Caustica's `fsrshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## Intel XeSS SDK (XeSS Super Resolution)

Caustica can build and distribute release artifacts that include Intel XeSS
runtime components for XeSS upscaling. The prebuilt `libxess.dll` runtime is
provided by Intel under the Intel Simplified Software License (Version
October 2022) and is not part of Caustica's LGPL grant:

<https://github.com/intel/xess/blob/main/LICENSE.txt>

Redistribution and use of those components must comply with Intel's license
terms. Bundled Intel runtime libraries may include files matching:

- `caustica/natives/windows-x64/libxess.dll`

Caustica's `xessshim` native library is project-owned glue code and follows
Caustica's project license unless otherwise noted.

## NVIDIA NRD (Real-time Denoisers) + NRI

Caustica can build and distribute release artifacts that include NVIDIA NRD
(REBLUR denoisers) statically linked into the `nrdshim` native library, along
with its NRI rendering-interface dependency. Both are third-party components
with their own licenses (NRD: NVIDIA proprietary source license; NRI: MIT) and
are not licensed under the LGPL:

<https://github.com/NVIDIA-RTX/NRD/blob/master/LICENSE.txt>
<https://github.com/NVIDIA-RTX/NRI/blob/main/LICENSE.txt>

Redistribution and use of those components must comply with their license
terms. Bundled NRD binaries may include files matching:

- `caustica/natives/windows-x64/nrdshim.dll`

Caustica's `nrdshim` glue code is project-owned and follows Caustica's project
license except for the statically linked NRD/NRI portions noted above.
