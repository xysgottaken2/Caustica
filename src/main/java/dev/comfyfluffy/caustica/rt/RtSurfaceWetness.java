package dev.comfyfluffy.caustica.rt;

/**
 * Renderer-global wetness amount passed to the world shader once per frame.
 *
 * <p>This is intentionally world-global rather than block-persistent: it gives terrain a continuous
 * rain response without allocating a world-sized wetness texture or adding per-block CPU work. The
 * frame delta is capped to one second so alt-tabbing or a long render stall cannot make the surface
 * jump instantly between dry and soaked when rendering resumes.
 */
final class RtSurfaceWetness {
    static final float WET_SECONDS = 5.0f;
    static final float DRY_SECONDS = 30.0f;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final long MAX_FRAME_DELTA_NANOS = 1_000_000_000L;

    private float value;
    private long lastUpdateNanos = Long.MIN_VALUE;

    /** Updates and returns the 0..1 wetness value for the current frame using default timings. */
    float update(boolean raining, long nowNanos) {
        return update(raining, nowNanos, WET_SECONDS, DRY_SECONDS);
    }

    /** Updates with live-configured wet/dry durations (seconds). */
    float update(boolean raining, long nowNanos, float wetSeconds, float drySeconds) {
        if (lastUpdateNanos == Long.MIN_VALUE) {
            lastUpdateNanos = nowNanos;
            return value;
        }

        long elapsedNanos = nowNanos - lastUpdateNanos;
        lastUpdateNanos = nowNanos;
        if (elapsedNanos <= 0L) {
            return value;
        }

        long cappedDelta = Math.min(elapsedNanos, MAX_FRAME_DELTA_NANOS);
        double seconds = cappedDelta / NANOS_PER_SECOND;
        float duration = Math.max(0.1f, raining ? wetSeconds : drySeconds);
        float amount = (float) (seconds / duration);
        value = clamp(value + (raining ? amount : -amount));
        return value;
    }

    float value() {
        return value;
    }

    /** Clears wetness while disabled and resets the clock so re-enabling starts from dry ground. */
    void reset(long nowNanos) {
        value = 0.0f;
        lastUpdateNanos = nowNanos;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
