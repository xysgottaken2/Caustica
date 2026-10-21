package dev.comfyfluffy.caustica.rt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Vanilla's own precipitation question, in one place.
 *
 * <p>Minecraft decides rain-versus-snow per <em>column</em>, not per world: the biome at that spot
 * reports {@code RAIN}, {@code SNOW} or {@code NONE} (deserts, badlands, savannas), and the weather
 * renderer files the column into the rain or the snow sheet accordingly. That is the answer this
 * renderer needs, and asking it here — once, for every consumer — is what keeps the surface response
 * from disagreeing with itself across the frame.
 *
 * <p>The distinction matters because {@code Level.getRainLevel} is a single global storm value: it says
 * how hard the weather system is running, not what is falling out of it. A snow biome and a desert share
 * the same number as a jungle.
 *
 * <p>Sampling happens at the column's surface, never at an arbitrary height: temperature drives the
 * rain/snow split and temperature follows altitude, so a peak in a temperate biome is correctly snow
 * while the valley below it is rain.
 */
public final class RtPrecipitation {
    private RtPrecipitation() {
    }

    /**
     * True when liquid rain — not snow, and not a biome that never gets precipitation — falls on the
     * given column at the given surface height.
     *
     * @param level    the client level the column belongs to
     * @param blockX   column X
     * @param blockZ   column Z
     * @param surfaceY the column's motion-blocking surface height, or any Y at/above it
     */
    public static boolean isLiquidRain(ClientLevel level, int blockX, int blockZ, int surfaceY) {
        return precipitationAt(level, blockX, blockZ, surfaceY) == Biome.Precipitation.RAIN;
    }

    /**
     * The column's precipitation type, exactly as the game's weather renderer asks for it.
     *
     * <p>Public so the lightning scheduler can refuse to drop a bolt on a column where nothing is
     * falling, and so the terrain snapshot can bake the same answer into every prim it emits.
     */
    public static Biome.Precipitation precipitationAt(ClientLevel level, int blockX, int blockZ, int surfaceY) {
        if (level == null) {
            return Biome.Precipitation.NONE;
        }
        // getBiome() is 3D, so clamp into the level first; a height outside the world would otherwise ask
        // about a position that does not exist.
        int y = Math.clamp(surfaceY, level.getMinY(), level.getMaxY());
        BlockPos pos = new BlockPos(blockX, y, blockZ);
        return level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
    }
}
