package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Guards the semantics the settings UI's "Reset to Defaults" buttons are built on.
 *
 * <p>Every Caustica sub-screen starts with a per-page reset row and the hub with a global one, all of
 * them implemented as {@link CausticaConfig.RuntimeSetting#resetToDefault()} over a settings list. If a
 * future setting quietly stopped snapping back to its factory value — especially an angle setting whose
 * {@code set()} consumes degrees while the stored default is already radians — every Reset button would
 * lie to the player, so the contract is pinned here for every registered setting.
 */
final class RtConfigDefaultsTest {

    @Test
    void settingsRegistryIsPopulated() {
        assertFalse(CausticaConfig.settings().isEmpty(),
                "the settings registry must not be empty: the UI resets iterate it");
    }

    @Test
    void everySettingRestoresItsExactDefaultAfterAChange() {
        for (CausticaConfig.RuntimeSetting<?> setting : CausticaConfig.settings()) {
            Object factoryDefault = setting.defaultValue();
            perturb(setting);
            setting.resetToDefault();
            assertEquals(factoryDefault, setting.get(),
                    setting.key() + " did not snap back to its factory default after a change");
        }
    }

    @Test
    void radiansSettingsResetWithoutReapplyingTheInputTransform() {
        // Sun angular radius: stored in radians, but set() consumes degrees. A naive
        // set(defaultValue()) would convert the radian default a second time and pin the sun at
        // ~0.01 degrees — exactly the trap the custom resetToDefault() exists to avoid.
        CausticaConfig.FloatSetting sun = CausticaConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        float def = sun.value();
        sun.set(5.0f);
        assertNotEquals(def, sun.value(), 1.0e-9f, "perturbing the slider must change the value");
        sun.resetToDefault();
        assertEquals(def, sun.value(), "reset must restore the stored default verbatim");
        sun.set(0.6f); // the stock value written back through the public degrees path
        assertEquals(def, sun.value(), 1.0e-7f,
                "writing the stock degrees through set() must reproduce the stored radians");
    }

    @SuppressWarnings("unchecked")
    private static <T> void perturb(CausticaConfig.RuntimeSetting<T> setting) {
        T factoryDefault = setting.defaultValue();
        if (factoryDefault instanceof Boolean b) {
            ((CausticaConfig.RuntimeSetting<Boolean>) setting).set(!b);
        } else if (factoryDefault instanceof Integer i) {
            ((CausticaConfig.RuntimeSetting<Integer>) setting).set(i + 1);
        } else if (factoryDefault instanceof Float f) {
            ((CausticaConfig.RuntimeSetting<Float>) setting).set(f + 0.5f);
        } else {
            // String mode pickers (exposure/cloud style/tonemap) and the optional NGX path setting,
            // whose default is null. Sanitizers may map the junk value anywhere; the reset must still
            // land on the exact default.
            ((CausticaConfig.RuntimeSetting<String>) setting).set("perturbed-test-value");
        }
    }
}
