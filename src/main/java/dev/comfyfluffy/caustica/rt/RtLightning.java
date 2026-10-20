package dev.comfyfluffy.caustica.rt;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Random;

/**
 * Render-thread lightning scheduler. It does not mutate the Minecraft world or spawn a server entity;
 * instead it publishes a short-lived strike position and pulse envelope to the ray-tracing shaders.
 * The sky shader draws the jagged bolt and flash, the shading pass uses the position as a local point
 * light, and the client plays distance-delayed positional thunder for each strike. Strike timing follows
 * a randomized arrival process whose expected rate is configured as strikes per minute.
 */
final class RtLightning {
    private static final long FLASH_DURATION_NANOS = 180_000_000L;
    private static final float STRIKE_MIN_DISTANCE = 20.0f;
    private static final float STRIKE_DISTANCE_RANGE = 34.0f;

    record State(double x, double y, double z, float intensity) {
        static final State NONE = new State(0.0, 0.0, 0.0, 0.0f);
    }

    private final Random random = new Random();
    private long nextStrikeNanos;
    private long flashStartedNanos = -1L;
    private double strikeX;
    private double strikeY;
    private double strikeZ;

    State update(ClientLevel level, double cameraX, double cameraY, double cameraZ, float rain,
                 boolean enabled, float strikesPerMinute, float configuredIntensity,
                 boolean soundEnabled, float soundVolume, long nowNanos) {
        if (!enabled || level == null || rain < 0.08f || configuredIntensity <= 0.0f) {
            nextStrikeNanos = 0L;
            flashStartedNanos = -1L;
            return State.NONE;
        }

        if (nextStrikeNanos == 0L) {
            nextStrikeNanos = nowNanos + nextDelay(strikesPerMinute);
        }
        if (nowNanos >= nextStrikeNanos) {
            chooseStrike(level, cameraX, cameraY, cameraZ);
            flashStartedNanos = nowNanos;
            nextStrikeNanos = nowNanos + nextDelay(strikesPerMinute);
            // Use the vanilla thunder sound at the actual strike location. The distance-delay flag
            // makes a nearby flash crack sooner than a distant one, and WEATHER respects the game's
            // weather-volume slider. A broad enough source volume lets strikes at the far edge of the
            // procedural radius remain audible; positional attenuation still makes them quieter.
            if (soundEnabled && soundVolume > 0.0f) {
                float pitch = 0.82f + random.nextFloat() * 0.16f;
                float volume = 4.0f * Math.clamp(soundVolume, 0.0f, 2.0f);
                level.playLocalSound(strikeX, strikeY, strikeZ, SoundEvents.LIGHTNING_BOLT_THUNDER,
                        SoundSource.WEATHER, volume, pitch, true);
            }
        }

        if (flashStartedNanos < 0L) {
            return State.NONE;
        }
        float elapsed = (nowNanos - flashStartedNanos) / (float) FLASH_DURATION_NANOS;
        if (elapsed < 0.0f || elapsed >= 1.0f) {
            flashStartedNanos = -1L;
            return State.NONE;
        }

        // Compact natural-looking double flash: sharp initial discharge, a brief lull, then a weaker
        // secondary pulse and a fast decay. Duration is intentionally short to feel like lightning.
        float pulse;
        if (elapsed < 0.13f) pulse = 1.0f;
        else if (elapsed < 0.28f) pulse = 0.08f;
        else if (elapsed < 0.46f) pulse = 0.62f;
        else if (elapsed < 0.60f) pulse = 0.04f;
        else pulse = 0.28f * (1.0f - (elapsed - 0.60f) / 0.40f);
        float weatherScale = 0.75f + 0.25f * Math.clamp(rain, 0.0f, 1.0f);
        return new State(strikeX, strikeY, strikeZ, Math.max(0.0f, pulse * configuredIntensity * weatherScale));
    }

    private long nextDelay(float strikesPerMinute) {
        double rate = Math.max(1.0, Math.min(60.0, strikesPerMinute));
        double meanIntervalSeconds = 60.0 / rate;
        // Exponential inter-arrival times yield the requested average strike count per minute while
        // preserving natural randomness instead of a metronomic fixed interval. A tiny lower bound
        // prevents a pathological near-zero random sample from scheduling two flashes in one frame.
        double sample = -Math.log(Math.max(1.0e-6, 1.0 - random.nextDouble()));
        double seconds = Math.max(0.25, meanIntervalSeconds * sample);
        return (long) (seconds * 1_000_000_000.0);
    }

    private void chooseStrike(ClientLevel level, double cameraX, double cameraY, double cameraZ) {
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = STRIKE_MIN_DISTANCE + random.nextDouble() * STRIKE_DISTANCE_RANGE;
        int blockX = (int) Math.floor(cameraX + Math.cos(angle) * distance);
        int blockZ = (int) Math.floor(cameraZ + Math.sin(angle) * distance);
        // Prefer solid-ground height rather than the top of leaves, so the flash visibly lights nearby
        // terrain. The 20-54 block radius keeps the strike inside the normally loaded local area.
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        if (surfaceY <= level.getMinY()) {
            surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
        }
        strikeX = blockX + 0.5;
        strikeY = Math.max(level.getMinY() + 1, surfaceY);
        strikeZ = blockZ + 0.5;
    }
}
