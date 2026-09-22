package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.fsr.FsrRuntime;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.xess.XessRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Upscaler selection support for the Video Settings UI: which upscaler values the selector offers,
 * which one is currently active, and whether DLSS Frame Generation is supported on this machine.
 *
 * <p>The upscaler selector maps onto the existing per-backend enable switches instead of introducing a
 * third source of truth: {@code dlss-rr.enabled} drives the DLSS Ray Reconstruction path exactly as
 * before, and {@code fsr.enabled} is reserved for the upcoming FSR 3 backend. The renderer keeps
 * reading the backend switches it always read ({@code RtDlssRr.enabled()}), so nothing downstream
 * changes when the selector flips a mode.
 */
public final class RtUpscalerSupport {
    public static final String MODE_NONE = "none";
    public static final String MODE_DLSS = "dlss";
    public static final String MODE_FSR3 = "fsr3";
    public static final String MODE_XESS = "xess";

    private RtUpscalerSupport() {
    }

    /**
     * The values the upscaler selector offers on this machine. FSR 3 / XeSS appear only once their
     * backends report available (see {@link #fsrUpscalingAvailable()} /
     * {@link #xessUpscalingAvailable()}) — until then the selector offers Off/DLSS, and the
     * missing backend's plumbing (config, translations, mode mapping) waits ready for it.
     */
    public static List<String> upscalerValues() {
        List<String> values = new ArrayList<>();
        values.add(MODE_NONE);
        values.add(MODE_DLSS);
        if (fsrUpscalingAvailable()) {
            values.add(MODE_FSR3);
        }
        if (xessUpscalingAvailable()) {
            values.add(MODE_XESS);
        }
        return values;
    }

    /** The upscaler mode implied by the current backend switches. */
    public static String currentUpscalerMode() {
        if (CausticaConfig.Rt.Fsr.ENABLED.value()) {
            return MODE_FSR3;
        }
        if (CausticaConfig.Rt.Xess.ENABLED.value()) {
            return MODE_XESS;
        }
        if (CausticaConfig.Rt.DlssRr.ENABLED.value()) {
            return MODE_DLSS;
        }
        return MODE_NONE;
    }

    /**
     * Apply an upscaler selection. Exactly one backend switch is on afterwards; the three are
     * mutually exclusive even though the config file lets a hand-edit set several (the renderer
     * resolves that to RR &gt; FSR 3 &gt; XeSS, then re-normalizes on the next change).
     */
    public static void applyUpscalerMode(String mode) {
        CausticaConfig.Rt.Fsr.ENABLED.set(MODE_FSR3.equals(mode));
        CausticaConfig.Rt.Xess.ENABLED.set(MODE_XESS.equals(mode));
        CausticaConfig.Rt.DlssRr.ENABLED.set(MODE_DLSS.equals(mode));
    }

    /**
     * Whether the AMD FSR 3 upscaler can run on this system. The backend (native/fsr_shim wrapping
     * the signed AMD FidelityFX Vulkan runtime, bundled per-platform by gradle) is Windows-only for
     * now — the SDK ships prebuilt runtimes for Windows, and a Linux build means compiling the
     * ffx-api VK backend from source (follow-up). Where the runtime is not bundled the selector
     * simply does not offer FSR 3.
     */
    public static boolean fsrUpscalingAvailable() {
        return FsrRuntime.platformSupported();
    }

    /**
     * Whether the Intel XeSS upscaler can run on this system. Two gates: the bundled runtime
     * (native/xess_shim + Intel's libxess.dll, Windows-only like FSR 3) AND XeSS's required device
     * features actually enabled on the Vulkan device (shaderStorageImageWriteWithoutFormat +
     * mutableDescriptorType, injected at vkCreateDevice time by RtDeviceBringup). A GPU missing
     * them can never run XeSS, so the selector must not offer it there.
     */
    public static boolean xessUpscalingAvailable() {
        return XessRuntime.platformSupported() && RtDeviceBringup.xessFeaturesEnabled();
    }

    /**
     * DLSS Frame Generation hardware support, for gating the FG toggle in Video Settings.
     *
     * <p>Order of preference, most authoritative first:
     * <ol>
     *   <li>The driver's own NGX capability query: once NGX is initialized (first DLSS render),
     *       {@code NVSDK_NGX_Parameter_FrameGeneration_Available} via
     *       {@code NVSDK_NGX_VULKAN_GetCapabilityParameters} answers directly — this is what NVIDIA
     *       documents as the feature-availability check, and what RTX 40/50-series gating actually
     *       means at the driver level.</li>
     *   <li>Before NGX is up (e.g. the options screen on the main menu, where initializing NGX purely
     *       for a UI hint would be invasive), a name-based heuristic in {@link RtDeviceBringup} that
     *       treats anything unrecognized as unsupported.</li>
     * </ol>
     */
    public static boolean dlssFrameGenerationSupported() {
        NgxLibrary lib = NgxRuntime.INSTANCE.library();
        if (lib != null && lib.hasDlssg()) {
            // NGX is up: ask the driver, not the device name.
            return lib.dlssgAvailable();
        }
        return RtDeviceBringup.looksLikeRtxFrameGenerationSeries();
    }
}
