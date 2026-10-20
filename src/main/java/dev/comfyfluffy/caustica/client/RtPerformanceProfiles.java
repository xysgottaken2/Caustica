package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.util.Objects;

/**
 * Safe, live-applied path-tracing profiles exposed by the Quality &amp; Performance screen.
 *
 * <p>These profiles reduce work in known hot paths (path depth and emitter RIS candidate evaluation)
 * and optionally disable material/water effects that add per-hit GPU work. They do not change the
 * selected upscaler, DLSS quality, frame generation, ReSTIR's master toggle, cloud settings, Vulkan
 * resources, or Minecraft/Fabric integration. Every changed setting is already read per frame, so
 * applying a profile needs no renderer restart or buffer rebuild.
 */
public final class RtPerformanceProfiles {
    public enum Profile {
        PERFORMANCE("performance"),
        BALANCED("balanced");

        private final String translationKey;

        Profile(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return this.translationKey;
        }
    }

    private RtPerformanceProfiles() {
    }

    /** Apply the selected budget while leaving the player's upscaler and unrelated visual settings alone. */
    public static void apply(Profile profile) {
        Objects.requireNonNull(profile, "profile");

        CausticaConfig.Rt.Composite.SPP.set(1);
        switch (profile) {
            case PERFORMANCE -> {
                // The factory default traces four secondary bounces and tests eight light candidates
                // at each shading point. These lower budgets cut repeated shader work without changing
                // ray geometry, queue layouts, or the 26.3 rendering ABI.
                CausticaConfig.Rt.Composite.MAX_BOUNCES.set(2);
                CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(2);
                CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS.set(0);
                CausticaConfig.Rt.Composite.SSS.set(false);
                CausticaConfig.Rt.Composite.PARALLAX_ENABLED.set(false);
                CausticaConfig.Rt.Composite.WATER_WAVES.set(false);
            }
            case BALANCED -> {
                CausticaConfig.Rt.Composite.MAX_BOUNCES.set(3);
                CausticaConfig.Rt.Lights.RIS_CANDIDATES.set(4);
                CausticaConfig.Rt.Lights.RESTIR_SPATIAL_NEIGHBOURS.set(2);
                CausticaConfig.Rt.Composite.SSS.set(true);
                CausticaConfig.Rt.Composite.PARALLAX_ENABLED.set(true);
                CausticaConfig.Rt.Composite.WATER_WAVES.set(true);
            }
        }
    }
}
