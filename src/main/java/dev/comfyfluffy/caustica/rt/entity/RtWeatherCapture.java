package dev.comfyfluffy.caustica.rt.entity;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Vanilla rain/snow columns, captured as real ray-traced geometry.
 *
 * <h2>Why this exists at all</h2>
 * Minecraft's rain is <em>not</em> a {@code ParticleEngine} effect, so {@link RtEntities}' particle path
 * never sees it. It is a dedicated pass — {@code WeatherEffectRenderer} — that lives inside
 * {@code LevelRenderer.render}, which Caustica cancels outright. That is the whole reason rain vanished
 * under the ray-traced renderer: nothing was broken, the pass simply never ran.
 *
 * <p>The fix is deliberately <b>not</b> a new procedural effect. Earlier attempts invented a synthetic
 * streak field (bars that read as falling iron/meteors) and a 2D screen overlay (a panel pasted on the
 * HUD). Both failed for the same reason: they were <em>guesses</em> at what rain looks like instead of
 * the thing the game already computes. This class throws that away and replays vanilla's own geometry.
 *
 * <h2>What is reused verbatim</h2>
 * The columns are read from {@link WeatherRenderState}, which vanilla's {@code LevelExtractor} still
 * fills every frame — extraction happens in {@code GameRenderer.extract}, <em>before</em> and entirely
 * independently of the {@code LevelRenderer.render} call Caustica cancels. So the CPU-side weather
 * simulation, all of it, is already running and correct:
 *
 * <ul>
 *   <li><b>Placement</b> — one column per (x, z) inside the {@code weatherRadius} option, skipping
 *       biomes with no precipitation and unloaded chunks.</li>
 *   <li><b>Vertical extent</b> — {@code bottomY}/{@code topY} are clamped to the {@code MOTION_BLOCKING}
 *       heightmap, which is exactly what makes rain stop at the ground, at leaves, and at a roof instead
 *       of raining through the world. This is the "collides with the 3D world" behaviour, and it is free:
 *       it is baked into the column extents vanilla already computed.</li>
 *   <li><b>Fall animation</b> — the scrolling {@code vOffset} in {@code ColumnInstance} advances with
 *       game time, so the drops genuinely fall rather than sitting still.</li>
 *   <li><b>Rain vs. snow</b> — separate column lists, separate textures, separate max alpha.</li>
 * </ul>
 *
 * <p>The quad construction below is a line-for-line port of {@code WeatherEffectRenderer.renderInstances}
 * — the same {@code columnSizeX/Z} lookup table, the same distance-faded alpha, the same {@code y * 0.25}
 * texture V mapping. Positions come out camera-relative (as vanilla emits them) and are shifted into the
 * renderer's rebased space so the TLAS instance transform stays identity, matching {@link
 * RtParticleCapture}. The single deliberate divergence: the column the camera stands inside is dropped
 * before it reaches the BLAS. Vanilla never draws it either — its NaN orientation entry kills the quad
 * in the rasteriser — but here the quad must be skipped explicitly, because a camera-facing sheet with
 * the eye on its plane degenerates into a screen-spanning vertical line under ray tracing (see
 * {@link #captureColumns}).
 *
 * <h2>How it reaches the screen</h2>
 * The quads are pushed through {@link RtEntityCapture} into the same combined particle mesh/BLAS the
 * {@code ParticleEngine} billboards use, so they inherit {@code PARTICLE_BIT}, the primary-ray-only
 * instance mask, the any-hit cutout bucket, and {@code MATERIAL_PARTICLE} shading in raygen. Because they
 * are ordinary geometry in the acceleration structure, a ray fired at a wall stops at the wall: rain
 * behind terrain is occluded by the depth of the trace itself, with no extra logic.
 *
 * <p>The V coordinate deliberately runs far outside 0..1 ({@code y * 0.25} over a column tens of blocks
 * tall, plus a scroll offset that grows with game time). That is vanilla's design — the sheet is meant to
 * tile — and it works here because the bindless entity sampler is created with {@code REPEAT} addressing
 * in all three axes, so the coordinate wraps instead of clamping the whole column to one stretched texel.
 */
public final class RtWeatherCapture {
    public static final RtWeatherCapture INSTANCE = new RtWeatherCapture();

    /** Vanilla's rain/snow sheet textures ({@code WeatherEffectRenderer.RAIN_LOCATION}). */
    private static final Identifier RAIN_LOCATION =
            Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW_LOCATION =
            Identifier.withDefaultNamespace("textures/environment/snow.png");

    /** Vanilla's per-column orientation table size ({@code RAIN_TABLE_SIZE} / {@code HALF_...}). */
    private static final int RAIN_TABLE_SIZE = 32;
    private static final int HALF_RAIN_TABLE_SIZE = 16;

    /** Max alpha for rain vs. snow, matching vanilla's two {@code renderInstances} calls. */
    private static final float RAIN_MAX_ALPHA = 1.0f;
    private static final float SNOW_MAX_ALPHA = 0.8f;

    /**
     * Floor applied to a column's <em>opacity</em> once it is drawn at all — <b>not</b> a delay.
     *
     * <p>This replaces an earlier {@code RAIN_ONSET} dead zone that suppressed the drops entirely until
     * the rain level passed 0.18. The intent there was sound: at rain 0.05 the sky's overcast blend is
     * only 5% grey (invisible) while a thin bright streak is already unmistakable, so feeding both the
     * same number made rain appear against a still-blue sky. But suppressing the drops fixed that by
     * deleting them, and the cost was a visible desync with the rest of the storm — vanilla's splash
     * particles on the ground start as soon as the rain level leaves zero, so the columns came in about
     * 1.7 seconds late and, symmetrically, vanished 1.7 seconds early. Rain hitting the ground with no
     * rain in the air.
     *
     * <p>The real problem was never <em>when</em> the drops appear, it is that a faint sheet reads as too
     * present. So fix the contrast instead of the timing: a column that is drawn is drawn from the first
     * frame the storm exists, but never fainter than this. The ramp then rides on top, and the drops
     * track the splash particles and the greying sky in lockstep.
     */
    private static final float MIN_COLUMN_OPACITY = 0.35f;

    /**
     * Smallest column alpha worth emitting, mirroring {@code ENTITY_ALPHA_CUTOFF} in {@code
     * world.rahit}.
     *
     * <p>The any-hit shader keeps a weather texel only when {@code texel.a * coverage} clears that
     * cutoff. The rain sheet's densest texel — the core of a drop — has an alpha of about 1, so a column
     * whose coverage is below the cutoff cannot produce a single surviving sample: not a faint sheet, an
     * absent one. Emitting it anyway spends a slot of the shared particle budget, a BLAS entry and a
     * ray test per pixel to draw nothing.
     *
     * <p>Kept deliberately in step with the shader constant rather than set to one 8-bit channel step
     * ({@code 1/255}). That smaller bound is the point below which the coverage byte rounds to zero, but
     * everything between it and the cutoff is equally invisible, so culling there would leave most of
     * the dead columns in the budget.
     */
    private static final float MIN_VISIBLE_ALPHA = 0.1f;

    /**
     * Vanilla's per-column billboard orientation table. Each (x, z) offset around the camera gets a fixed
     * horizontal facing, so neighbouring columns fan out instead of all facing the same way — this is what
     * stops the rain from looking like one flat wall. Copied from {@code WeatherEffectRenderer}'s
     * constructor rather than recomputed differently, so orientation matches vanilla exactly.
     */
    private final float[] columnSizeX = new float[RAIN_TABLE_SIZE * RAIN_TABLE_SIZE];
    private final float[] columnSizeZ = new float[RAIN_TABLE_SIZE * RAIN_TABLE_SIZE];

    private boolean loggedFailure;

    private RtWeatherCapture() {
        for (int z = 0; z < RAIN_TABLE_SIZE; z++) {
            for (int x = 0; x < RAIN_TABLE_SIZE; x++) {
                float deltaX = x - HALF_RAIN_TABLE_SIZE;
                float deltaZ = z - HALF_RAIN_TABLE_SIZE;
                float distance = Mth.length(deltaX, deltaZ);
                if (distance < 1.0e-4f) {
                    // The exact table centre is the column the camera is standing in: deltaX == deltaZ == 0,
                    // so vanilla's -deltaZ/distance is 0/0 = NaN. Vanilla gets away with it because a NaN
                    // vertex is simply dropped by the rasteriser. Here the quad is fed to a BLAS build
                    // instead, and a NaN-cornered triangle poisons that node's bounding box — every ray
                    // that tests it degenerates, which is exactly the thin vertical sliver that appeared
                    // after standing still long enough for the camera to settle inside one cell.
                    //
                    // Any unit direction is correct for a column centred on the camera (it is seen from
                    // every side at once), so pick a fixed one rather than propagating the NaN.
                    //
                    // For context: vanilla keeps the NaN at this entry, and the rasteriser then drops
                    // the whole quad whenever a vertex is NaN — so in vanilla the column the camera
                    // stands inside is never actually drawn. Verified against the 26.2
                    // WeatherEffectRenderer source (constructor stores -0.0f/0.0f; renderInstances has
                    // no special case for the centre cell).
                    columnSizeX[z * RAIN_TABLE_SIZE + x] = 1.0f;
                    columnSizeZ[z * RAIN_TABLE_SIZE + x] = 0.0f;
                    continue;
                }
                columnSizeX[z * RAIN_TABLE_SIZE + x] = -deltaZ / distance;
                columnSizeZ[z * RAIN_TABLE_SIZE + x] = deltaX / distance;
            }
        }
    }

    /** Master switch for ray-traced weather columns ({@code caustica.rt.weatherParticles}). */
    public static boolean enabled() {
        return CausticaConfig.Rt.Entities.WEATHER_ENABLED.value();
    }

    /**
     * Append this frame's rain and snow columns to {@code capture} (a particle-mode capture: the caller
     * has already set the any-hit alpha bucket and the camera-relative → rebased offset on
     * {@code out}).
     *
     * <p>Returns the number of columns captured, or 0 when it is not raining, the feature is off, or the
     * weather render state has not been populated (menus, dimension without precipitation).
     *
     * @param capture the shared entity/particle mesh accumulator
     * @param out     the {@link RtParticleCapture} adapter feeding {@code capture}, offset already set
     * @param camPos  this frame's camera position (columns are emitted relative to it, as vanilla does)
     * @param budget  max columns to capture, so weather cannot exhaust the particle budget on its own
     */
    public int capture(RtEntityCapture capture, RtParticleCapture out, Vec3 camPos, int budget) {
        if (!enabled() || budget <= 0) {
            return 0;
        }
        WeatherRenderState state = weatherState();
        if (state == null || state.intensity <= 0.0f || state.radius <= 0) {
            return 0;
        }
        float intensity = visualIntensity(state.intensity);
        // Player's density slider: thins the streaks through the same coverage lane distance fading
        // uses, so a lower setting reads as lighter rain rather than darker rain (see RAIN_DENSITY).
        intensity *= Math.clamp(CausticaConfig.Rt.Entities.RAIN_DENSITY.value(), 0f, 1f);
        if (intensity <= 0.0f) {
            return 0;
        }
        int captured = 0;
        // Tag every emitted sheet so raygen shades it UNLIT (see PRIM_WEATHER). The path tracer's
        // direct/indirect light read as blotchy, blown-out streaks on the rain; the sheets stay real
        // geometry (terrain occlusion and the heightmap clip are kept) but stop participating in
        // lighting, the way vanilla's own weather pass is effectively unlit.
        capture.currentPrimFlags = RtEntityCapture.PRIM_FLAG_WEATHER;
        capture.currentGlintArmorSlot = 0; // weather never carries a glint armour context
        try {
            captured += captureColumns(capture, out, state.rainColumns, camPos, RAIN_MAX_ALPHA,
                    state.radius, intensity, RAIN_LOCATION, budget - captured);
            captured += captureColumns(capture, out, state.snowColumns, camPos, SNOW_MAX_ALPHA,
                    state.radius, intensity, SNOW_LOCATION, budget - captured);
        } catch (Throwable t) {
            // Never take the whole RT frame down over weather: log once and render this frame dry.
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT weather capture failed; rain/snow will not be ray traced", t);
            }
            return captured;
        } finally {
            capture.currentPrimFlags = 0;
        }
        return captured;
    }

    /**
     * Reshape vanilla's raw rain level into the curve the <em>drops</em> should follow, so precipitation
     * and the overcast sky arrive and leave together.
     *
     * <p>Minecraft ramps {@code rainLevel} linearly from 0 to 1 over a few seconds when weather starts or
     * stops, and everything visual is driven from it. But the two things being driven have very different
     * perceptual responses to a small value:
     *
     * <ul>
     *   <li>the sky's overcast blend is <em>proportional</em> — at rain 0.05 it is 5% grey over blue,
     *       which is invisible. The sky still reads as a clear blue day.</li>
     *   <li>rain columns are <em>presence</em> — at rain 0.05 the sheets are faint but unmistakably
     *       there, because a thin streak against a bright sky is high contrast.</li>
     * </ul>
     *
     * <p>So with both fed the same number, the drops read as too present against a sky that has barely
     * changed. The earlier fix for that was a dead zone: draw nothing until the rain level cleared 0.18.
     * It did make the sky commit first, but it bought that by deleting the first ~1.7 seconds of
     * precipitation and, because the ramp is symmetric, the last ~1.7 seconds too. Everything else in
     * the storm keeps vanilla's timing — most visibly the splash particles on the ground, which start
     * the moment the rain level leaves zero — so the columns arrived late and left early against them.
     *
     * <p>Timing and contrast are separate problems, so they get separate fixes. Timing is left exactly
     * as vanilla computes it: the drops exist for precisely as long as the storm does. Contrast is
     * handled by {@link #MIN_COLUMN_OPACITY}, which stops a column from ever being drawn at the wispy
     * levels that looked wrong, and by the smoothstep below, which keeps the ramp eased rather than
     * linear so the sheets thicken naturally instead of snapping to full.
     *
     * <p>Deliberately applied here rather than to {@code weather.x}: that lane also drives the sun/moon
     * attenuation, and reshaping <em>that</em> is what would make the sky lag the storm.
     */
    private static float visualIntensity(float rainLevel) {
        float t = Mth.clamp(rainLevel, 0.0f, 1.0f);
        if (t <= 0.0f) {
            return 0.0f; // no storm at all: the only case that draws nothing
        }
        float eased = t * t * (3.0f - 2.0f * t); // smoothstep
        // Lift the eased ramp into [MIN_COLUMN_OPACITY, 1]. The drops are present from the storm's first
        // frame to its last, and the ramp controls how heavy they look rather than whether they exist.
        return MIN_COLUMN_OPACITY + eased * (1.0f - MIN_COLUMN_OPACITY);
    }

    /**
     * Vanilla's live weather columns for this frame, or null when unavailable.
     *
     * <p>Read from the shared {@code LevelRenderState} that {@code LevelExtractor.extract} fills each
     * frame. That extraction runs from {@code GameRenderer.extract}, which Caustica does not touch — only
     * {@code LevelRenderer.render} (the consumer) is cancelled. So this state is fully simulated and
     * current even though vanilla never drew it.
     */
    private static WeatherRenderState weatherState() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        return mc.gameRenderer.gameRenderState().levelRenderState.weatherRenderState;
    }

    /**
     * Emit one quad per column, exactly as {@code WeatherEffectRenderer.renderInstances} does.
     *
     * <p>Each column is a vertical sheet spanning {@code bottomY..topY} — the range vanilla already
     * clipped against the {@code MOTION_BLOCKING} heightmap — oriented by the {@code columnSize} table and
     * faded by squared horizontal distance. The V coordinate is {@code y * 0.25 + vOffset}, so the texture
     * tiles four blocks per repeat and scrolls downward with {@code vOffset} as game time advances: that
     * scroll is the falling motion.
     */
    private int captureColumns(RtEntityCapture capture, RtParticleCapture out,
                               List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camPos,
                               float maxAlpha, int radius, float intensity, Identifier texture, int budget) {
        if (columns == null || columns.isEmpty() || budget <= 0) {
            return 0;
        }
        // One bindless slot for the whole sheet: rain.png / snow.png are standalone textures, resolved
        // through the same registry entity textures use.
        capture.currentTexSlot = RtEntityTextures.INSTANCE.slotForTexture(texture);

        float radiusSq = (float) radius * radius;
        int camFloorX = Mth.floor(camPos.x);
        int camFloorZ = Mth.floor(camPos.z);
        int emitted = 0;

        for (WeatherEffectRenderer.ColumnInstance column : columns) {
            if (emitted >= budget) {
                break;
            }
            // Skip the column the camera stands inside — the one whose orientation-table entry is the
            // table centre. Vanilla never draws it: that entry is NaN (-0/0 in its constructor), and the
            // rasteriser discards any quad with a NaN vertex, so the accidental skip is part of the
            // geometry vanilla actually presents. This table stores a real direction instead (a
            // NaN-cornered triangle would poison its BLAS node — see the constructor), so the skip has
            // to be reproduced explicitly here.
            //
            // It is also right on its own merits, not just vanilla-faithful. Every rain sheet faces the
            // camera by construction — its span is perpendicular to the radial direction — so for this
            // one column the sheet's plane passes through the eye itself, or within half a block of it.
            // Rasterisation hides that: the quad projects to a near-zero-area sliver and vanishes. Ray
            // tracing has no equivalent rejection — the primary ray runs with tmin 0, so it grazes the
            // sheet edge-on at arbitrarily close range and the whole column draws itself as a
            // razor-thin vertical line across the screen. That is exactly the artefact that appears
            // after standing still (while walking, the camera keeps changing cells, so no single
            // degenerate sheet persists long enough to be noticed). Losing half a block of rain
            // directly around the eye is invisible in practice; the line was not.
            if (column.x() == camFloorX && column.z() == camFloorZ) {
                continue;
            }
            float relativeX = (float) (column.x() + 0.5 - camPos.x);
            float relativeZ = (float) (column.z() + 0.5 - camPos.z);
            float distanceSq = (float) Mth.lengthSquared(relativeX, relativeZ);
            float alpha = Mth.lerp(Math.min(distanceSq / radiusSq, 1.0f), maxAlpha, 0.5f) * intensity;
            // Drop columns the any-hit cutout will reject outright. Below MIN_VISIBLE_ALPHA not even a
            // drop's core texel survives, so the sheet is built, budgeted and traced only to be
            // discarded by every ray that touches it; skipping it here keeps the shared particle budget
            // for columns that actually draw.
            //
            // This is a cost guard, not the visibility fix: the storm-opening flash came from the shader
            // reading a zero coverage byte as fully opaque, which is fixed in world.rahit. Culling here
            // would have hidden that flash for the first stretch of the ramp while leaving it intact
            // everywhere else the byte lands on zero.
            if (alpha < MIN_VISIBLE_ALPHA) {
                continue;
            }
            // Vanilla's per-column alpha is a raster blend weight. The RT particle path has no blend
            // stage, so it goes into the ALPHA lane only: RtEntityCapture stores it as the primitive
            // coverage multiplier (aux0) and world.rahit folds it into the stochastic cutout together
            // with the sheet texel's own alpha. Fading by *coverage* is what a blend weight actually
            // means — fewer drop samples survive with distance — so the far columns thin out instead of
            // changing colour.
            //
            // That thinning only works because world.rahit dithers weather coverage instead of
            // thresholding it (see the PRIM_WEATHER branch there). Under a fixed cutout the multiplier
            // is all-or-nothing per texel: an opaque drop core draws at full strength until the fade
            // finally crosses the cutoff and the whole sheet pops out together, which is what made both
            // the distance falloff and the rain-density option look like they did nothing.
            //
            // The RGB stays WHITE on purpose. An earlier version also scaled RGB by the same factor to
            // reproduce the fade, but tint is not opacity: world.rchit multiplies it straight into the
            // albedo, so it *darkened* the drops rather than thinning them. That is what produced the
            // patch of rain that looked brighter than the rest — near columns kept a bright tint while
            // columns a few blocks further out were shaded progressively darker, and the boundary
            // between two adjacent alpha steps read as a visible seam across the sheet.
            int color = ARGB.white(alpha);

            // The orientation table is indexed by the column's offset from the camera, biased to the
            // table centre. Columns beyond the table (a radius option larger than 16) would index out of
            // bounds, so clamp — vanilla's radius maxes at 10 and never hits this, but a modded/config
            // value must not crash the renderer.
            int tableX = Mth.clamp(column.x() - camFloorX + HALF_RAIN_TABLE_SIZE, 0, RAIN_TABLE_SIZE - 1);
            int tableZ = Mth.clamp(column.z() - camFloorZ + HALF_RAIN_TABLE_SIZE, 0, RAIN_TABLE_SIZE - 1);
            int index = tableZ * RAIN_TABLE_SIZE + tableX;

            float halfSizeX = columnSizeX[index] / 2.0f;
            float halfSizeZ = columnSizeZ[index] / 2.0f;
            float x0 = relativeX - halfSizeX;
            float x1 = relativeX + halfSizeX;
            float y1 = (float) (column.topY() - camPos.y);
            float y0 = (float) (column.bottomY() - camPos.y);
            float z0 = relativeZ - halfSizeZ;
            float z1 = relativeZ + halfSizeZ;
            float u0 = column.uOffset();
            float u1 = column.uOffset() + 1.0f;
            float v0 = column.bottomY() * 0.25f + column.vOffset();
            float v1 = column.topY() * 0.25f + column.vOffset();

            // Same winding vanilla uses. The adapter buffers a vertex until the next addVertex, so the
            // final vertex of each quad is committed by flush() below.
            out.addVertex(x0, y1, z0).setUv(u0, v0).setColor(color);
            out.addVertex(x1, y1, z1).setUv(u1, v0).setColor(color);
            out.addVertex(x1, y0, z1).setUv(u1, v1).setColor(color);
            out.addVertex(x0, y0, z0).setUv(u0, v1).setColor(color);
            out.flush();
            emitted++;
        }
        return emitted;
    }
}
