package dev.comfyfluffy.caustica.rt;

import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.Optional;

/**
 * Vanilla's authored cloud shape, made available to the ray tracer.
 *
 * <h2>Why this exists</h2>
 * Vanilla's classic clouds are not generated: {@code CloudRenderer.prepare} reads
 * {@code textures/environment/clouds.png} — one pixel per 12-block cell — and extrudes every pixel
 * whose alpha survives {@code isCellEmpty} ({@code alpha >= 10}) into a 4-block box. Cloud SHAPE is
 * therefore authored data, and it is exactly what players expect when they pick the classic style:
 * the mix of large masses, small puffs and open gaps that no procedural field reproduces by tuning.
 * The ray-traced classic deck consumes the same data, so it inherits those shapes for free — and with
 * shape given rather than generated, the whole class of "fix one coverage constant, break another"
 * bugs disappears with it.
 *
 * <h2>What is published</h2>
 * A bit-packed occupancy bitmap: one bit per cell, 256x256 cells = 8 KiB, LSB-first within each
 * 32-bit word. {@link RtComposite} writes the words into the frame's BDA push-ring slot (the same
 * transport the DH ready mask uses) and hands the shader the slot-relative device address through
 * {@code WorldPush.cloudCellsAddr}. The shader masks cell indices to 256, matching the texture's own
 * tiling, which makes the deck exactly periodic over 3072 blocks — the anchor wrap is seamless by
 * construction, the property the noise deck needs its power-of-two constants for.
 *
 * <h2>Robustness</h2>
 * The map is published only when the texture exists and is exactly 256x256 (the size the shader's
 * mask assumes). Anything else — texture missing, a pack shipping other dimensions, a decode failure —
 * leaves the address at 0 and the shader degrades to its previous noise-quantised classic deck, so a
 * resource pack can never take the clouds away. Reloads (F3+T, pack changes) invalidate the cache;
 * the next frame re-reads the texture.
 */
public final class RtCloudCells {
    public static final RtCloudCells INSTANCE = new RtCloudCells();

    private static final Identifier CLOUDS_LOCATION =
            Identifier.withDefaultNamespace("textures/environment/clouds.png");

    /** The shader's mask assumes exactly this many cells per side — see clouds.slang. */
    public static final int MAP_CELLS = 256;
    /** Occupancy is bit-packed, 32 cells per word. */
    public static final int MAP_WORDS = MAP_CELLS * MAP_CELLS / 32;
    /** Upload footprint inside the push ring, kept 16-byte aligned like its neighbours. */
    public static final int MAP_BYTES = MAP_WORDS * 4;

    private volatile int[] packed;
    private volatile boolean loadFailed;

    private RtCloudCells() {
    }

    /** Drop the cached map; the next {@link #cells()} call re-reads the texture. */
    public void invalidate() {
        packed = null;
        loadFailed = false;
    }

    /**
     * This frame's occupancy words, or null when no usable map exists (missing texture, unexpected
     * dimensions, decode failure). Callers publish address 0 in that case and the shader falls back
     * to the noise deck.
     */
    public int[] cells() {
        int[] local = packed;
        if (local == null && !loadFailed) {
            local = load();
            packed = local;
            if (local == null) {
                loadFailed = true; // do not re-hit the resource manager every frame
            }
        }
        return local;
    }

    private static int[] load() {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager()
                    .getResource(CLOUDS_LOCATION);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream input = resource.get().open();
                 NativeImage image = NativeImage.read(input)) {
                if (image.getWidth() != MAP_CELLS || image.getHeight() != MAP_CELLS) {
                    CausticaMod.LOGGER.warn("clouds.png is {}x{}, not {}x{}; the ray-traced classic "
                                    + "deck falls back to its noise field",
                            image.getWidth(), image.getHeight(), MAP_CELLS, MAP_CELLS);
                    return null;
                }
                int[] words = new int[MAP_WORDS];
                int occupied = 0;
                for (int y = 0; y < MAP_CELLS; y++) {
                    for (int x = 0; x < MAP_CELLS; x++) {
                        // Vanilla's own cutoff: CloudRenderer.isCellEmpty is alpha < 10.
                        if (ARGB.alpha(image.getPixel(x, y)) >= 10) {
                            int index = y * MAP_CELLS + x;
                            words[index >> 5] |= 1 << (index & 31);
                            occupied++;
                        }
                    }
                }
                CausticaMod.LOGGER.info("RT cloud cell map loaded: {} of {} cells occupied",
                        occupied, MAP_CELLS * MAP_CELLS);
                return words;
            }
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Failed to load clouds.png for the RT classic deck", t);
            return null;
        }
    }
}
