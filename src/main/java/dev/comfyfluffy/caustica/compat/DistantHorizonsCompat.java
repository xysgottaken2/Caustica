package dev.comfyfluffy.caustica.compat;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight, API-independent Distant Horizons hybrid-rendering gate. */
public final class DistantHorizonsCompat {
    private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("distanthorizons");
    private static final byte[] EMPTY_QUADS = new byte[0];
    private static final ConcurrentHashMap<Long, LodMesh> LOD_MESHES = new ConcurrentHashMap<>();
    private static final AtomicLong LOD_REVISION = new AtomicLong();
    private static final AtomicLong MESH_VERSION = new AtomicLong();
    /**
     * Captures are scoped to the identity of Minecraft's current ClientLevel. DH section-position keys are
     * not world-unique, and uploads for a newly joined world may arrive before the RT proxy observes the
     * level switch. Keeping this scope here lets the upload hook discard the old world's CPU VBO cache
     * before inserting the first new-world mesh, without later wiping those fresh uploads from RT reset.
     */
    private static final Object WORLD_SCOPE_LOCK = new Object();
    private static volatile Object captureWorld;

    private DistantHorizonsCompat() {
    }

    /**
     * Captured DH quads are flattened into one tightly packed array per render pass. DH emits 64 bytes
     * per quad; dropping an incomplete tail preserves the old per-buffer decoder semantics while avoiding
     * one byte[] and one List node for every source VBO.
     */
    public record LodMesh(long key, long version, int originX, int originY, int originZ, int width,
                          int dataPointWidth, byte[] opaque, byte[] transparent) {
    }

    /** DH quality values relevant to RT LOD selection. */
    public record LodQuality(long signature, int maxDataPointWidth, int horizontalQualityRank,
                             String maxHorizontalResolution, String horizontalQuality) {
    }

    public static void captureLodBuffers(long pos, List<ByteBuffer> opaque, List<ByteBuffer> transparent) {
        captureLodBuffers(pos, null, opaque, transparent);
    }

    /**
     * Capture a DH upload together with the level wrapper that produced it. Multiplayer worlds do not
     * expose {@code getSinglePlayerLevel()}, so relying on that API loses the dimension metadata needed
     * to place every remote-server LOD mesh. The upload callback already carries the correct wrapper.
     */
    public static void captureLodBuffers(long pos, Object level, List<ByteBuffer> opaque,
                                         List<ByteBuffer> transparent) {
        if (!LOADED) return;
        ensureCurrentWorldScope();
        try {
            LodMesh previous = LOD_MESHES.get(pos);
            // DH can re-submit one or both passes unchanged. Compare source buffers directly against the
            // retained flattened bytes before allocating another multi-megabyte array, and reuse an unchanged
            // pass when only its counterpart was rebuilt.
            boolean sameOpaque = previous != null && quadBuffersEqual(opaque, previous.opaque);
            boolean sameTransparent = previous != null && quadBuffersEqual(transparent, previous.transparent);
            if (sameOpaque && sameTransparent) return;
            byte[] opaqueCopy = sameOpaque ? previous.opaque : copyQuadBuffers(opaque);
            byte[] transparentCopy = sameTransparent ? previous.transparent : copyQuadBuffers(transparent);

            int originX;
            int originY;
            int originZ;
            int width;
            if (previous != null) {
                // DhSectionPos metadata is immutable for a key. Reusing it avoids four reflective calls on
                // every VBO refresh; world changes clear this cache before the next capture.
                originX = previous.originX;
                originY = previous.originY;
                originZ = previous.originZ;
                width = previous.width;
            } else {
                int[] corner = Api.INSTANCE.minCorner(pos, level);
                originX = corner[0];
                originY = corner[1];
                originZ = corner[2];
                width = corner[3];
            }
            // Globally monotonic so a section that was pruned and later re-created can never collide
            // with a still-published proxy entry that happened to have the same per-key version number.
            long version = MESH_VERSION.incrementAndGet();
            int dataPointWidth = estimateDataPointWidth(opaqueCopy, transparentCopy, width);
            LOD_MESHES.put(pos, new LodMesh(pos, version, originX, originY, originZ, width,
                    dataPointWidth, opaqueCopy, transparentCopy));
            LOD_REVISION.incrementAndGet();
        } catch (Throwable ignored) {
            // DH is optional and changes internals between releases. A failed capture must never break the
            // renderer; the existing mesh (if any) remains usable until a later successful upload.
        }
    }

    public static List<LodMesh> lodMeshesSnapshot() {
        if (!enabled()) return List.of();
        List<LodMesh> voxy = VoxyCompat.active() ? VoxyCompat.meshes() : List.of();
        // Never render two independently simplified copies of the same horizon. Prefer Voxy while it has
        // an active snapshot, then fall back to DH during Voxy bootstrap or when only DH is installed.
        if (!voxy.isEmpty()) return voxy;
        if (!LOADED) return List.of();
        ensureCurrentWorldScope();
        try {
            Set<Long> active = RenderApi.INSTANCE.activeLodPositions();

            // On dedicated servers DH can upload valid client-side LOD VBOs before RenderParams has been
            // validated, and some DH versions expose an empty/mismatched enabled-section list for remote
            // level wrappers. Returning an empty snapshot here made the RT bootstrap retry forever while
            // singleplayer worked. Captured buffers are already cleared on ClientLevel changes and filtered
            // by the RT proxy radius, so they are a safe multiplayer fallback.
            if (active.isEmpty()) {
                return new ArrayList<>(LOD_MESHES.values());
            }

            // Captures used to accumulate forever while the player travelled. Keep a generous grace
            // budget before pruning: DH can transiently omit a section while swapping LOD levels, and deleting
            // it immediately would create a hole until that VBO happened to be uploaded again.
            long retainedBudget = Math.min(Integer.MAX_VALUE, (long) active.size() * 2L + 128L);
            if (LOD_MESHES.size() > retainedBudget) {
                for (var entry : LOD_MESHES.entrySet()) {
                    long pos = entry.getKey();
                    if (!active.contains(pos)) LOD_MESHES.remove(pos, entry.getValue());
                }
            }

            ArrayList<LodMesh> result = new ArrayList<>(Math.min(active.size(), LOD_MESHES.size()));
            for (long pos : active) {
                LodMesh mesh = LOD_MESHES.get(pos);
                if (mesh != null) result.add(mesh);
            }
            // A non-empty active list can still use section identities from a different remote-level
            // wrapper generation. Prefer the captured current-world set over suppressing DH completely.
            return result.isEmpty() && !LOD_MESHES.isEmpty()
                    ? new ArrayList<>(LOD_MESHES.values())
                    : result;
        } catch (Throwable ignored) {
            // Renderer-internal active-section reflection is less stable on multiplayer DH paths. The
            // upload hook is independent and already gave us valid current-world VBOs, so retain them.
            return LOD_MESHES.isEmpty() ? List.of() : new ArrayList<>(LOD_MESHES.values());
        }
    }

    /**
     * Ensure the capture map belongs to the ClientLevel that is active now. This method is called from both
     * the DH upload hook and the RT snapshot path: whichever observes the world transition first performs
     * the clear. Fresh uploads from the new world are therefore never erased by a later RT-world reset.
     */
    private static void ensureCurrentWorldScope() {
        Object currentWorld = Minecraft.getInstance().level;
        if (captureWorld == currentWorld) return;
        synchronized (WORLD_SCOPE_LOCK) {
            if (captureWorld == currentWorld) return;
            LOD_MESHES.clear();
            captureWorld = currentWorld;
            LOD_REVISION.incrementAndGet();
        }
    }

    /** Drop captured buffers when disabling the integration or performing final shutdown. */
    public static void clearCapturedLods() {
        VoxyCompat.reset();
        synchronized (WORLD_SCOPE_LOCK) {
            if (!LOD_MESHES.isEmpty()) {
                LOD_MESHES.clear();
                LOD_REVISION.incrementAndGet();
            }
            // Adopt the currently visible level. A later real level-identity change still advances the
            // revision through ensureCurrentWorldScope(), while same-world re-uploads can repopulate normally.
            captureWorld = Minecraft.getInstance().level;
        }
    }

    public static long lodRevision() {
        if (LOADED) ensureCurrentWorldScope();
        long dh = LOADED ? LOD_REVISION.get() : 0L;
        long voxy = VoxyCompat.revision();
        return dh ^ Long.rotateLeft(voxy, 29);
    }

    private static long quadByteCount(List<ByteBuffer> buffers) {
        long total = 0L;
        for (ByteBuffer source : buffers) {
            if (source != null) total += source.remaining() & ~63L;
        }
        return total;
    }

    private static boolean quadBuffersEqual(List<ByteBuffer> buffers, byte[] expected) {
        long total = quadByteCount(buffers);
        if (total != expected.length) return false;
        int expectedOffset = 0;
        for (ByteBuffer source : buffers) {
            if (source == null) continue;
            ByteBuffer actual = source.duplicate();
            int bytes = actual.remaining() & ~63;
            if (bytes == 0) continue;
            actual.limit(actual.position() + bytes);
            ByteBuffer expectedSlice = ByteBuffer.wrap(expected, expectedOffset, bytes).slice();
            if (actual.slice().mismatch(expectedSlice) != -1) return false;
            expectedOffset += bytes;
        }
        return true;
    }

    private static byte[] copyQuadBuffers(List<ByteBuffer> buffers) {
        long total = quadByteCount(buffers);
        if (total == 0L) return EMPTY_QUADS;
        if (total > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Distant Horizons LOD buffer set is too large: " + total);
        }

        byte[] out = new byte[(int) total];
        int offset = 0;
        for (ByteBuffer source : buffers) {
            if (source == null) continue;
            ByteBuffer copy = source.duplicate();
            int bytes = copy.remaining() & ~63;
            if (bytes == 0) continue;
            copy.limit(copy.position() + bytes);
            copy.get(out, offset, bytes);
            offset += bytes;
        }
        return out;
    }

    public static boolean enabled() {
        return LOADED || VoxyCompat.active();
    }

    /** Update optional providers on the render thread before the RT proxy observes their revision. */
    public static void tickOptionalSources() {
        VoxyCompat.tick();
    }

    /**
     * Ask DH to discard only its in-memory render/quadtree data. Keep Caustica's last captured CPU meshes
     * as an atomic fallback while DH repopulates its quadtree: clearing both caches at once would allow the
     * first partial upload batch to replace the complete RT proxy and briefly leave large holes. Fresh DH
     * uploads replace these retained entries by key/version and trigger the normal revision rebuild. No
     * saved LOD database or world data is deleted.
     */
    public static boolean reloadRenderDataCache() {
        if (!enabled()) return false;
        boolean reloaded = VoxyCompat.reset();
        if (!LOADED) return reloaded;
        try {
            return ReloadApi.INSTANCE.clearRenderDataCache() || reloaded;
        } catch (Throwable ignored) {
            return reloaded;
        }
    }


    /** Current DH horizontal quality. Changes are polled by the RT proxy to trigger immediate refinement. */
    public static LodQuality lodQuality() {
        if (!enabled()) return new LodQuality(0L, 16, 2, "UNKNOWN", "UNKNOWN");
        if (VoxyCompat.active()) {
            // The set of widths in a Voxy snapshot is streaming availability, not a user quality setting.
            // Treating its transient maximum as configuration made every newly-arrived 2/4/8-block ring
            // cancel thousands of in-flight BLAS builds and restart the complete proxy. Voxy's finest
            // configured representation is always one block; only an actual distance change is a new
            // quality signature.
            int distance = Math.max(1, VoxyCompat.renderDistanceChunks());
            long signature = 0x564F585900000000L ^ ((long) distance << 16);
            return new LodQuality(signature, 1, 4, "VOXY_STREAMED", "DYNAMIC");
        }
        try {
            return Api.INSTANCE.lodQuality();
        } catch (Throwable ignored) {
            return new LodQuality(0L, 16, 2, "UNKNOWN", "UNKNOWN");
        }
    }

    private static int estimateDataPointWidth(byte[] opaque, byte[] transparent, int fallbackWidth) {
        int[] histogram = new int[16];
        sampleDataPointWidths(opaque, histogram);
        sampleDataPointWidths(transparent, histogram);
        int bestBucket = -1;
        int bestCount = 0;
        for (int i = 0; i < histogram.length; i++) {
            if (histogram[i] > bestCount) {
                bestCount = histogram[i];
                bestBucket = i;
            }
        }
        if (bestBucket < 0) return Math.max(1, Integer.highestOneBit(Math.max(1, fallbackWidth)));
        return 1 << bestBucket;
    }

    private static void sampleDataPointWidths(byte[] bytes, int[] histogram) {
        int quads = bytes.length / 64;
        if (quads == 0) return;
        int samples = Math.min(1024, quads);
        for (int sample = 0; sample < samples; sample++) {
            int quad = (int) (((long) sample * quads) / samples) * 64;
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = quad + vertex * 16;
                int x = u16le(bytes, offset);
                int z = u16le(bytes, offset + 4);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            int span = Math.max(maxX - minX, maxZ - minZ);
            if (span <= 0) continue;
            int rounded = Integer.highestOneBit(span);
            if (rounded < span && rounded < (1 << 15)) rounded <<= 1;
            int bucket = Math.min(histogram.length - 1, Integer.numberOfTrailingZeros(rounded));
            histogram[bucket]++;
        }
    }

    private static int u16le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    public static int renderDistanceChunks() {
        if (!enabled()) return 0;
        int voxyDistance = VoxyCompat.renderDistanceChunks();
        if (!LOADED) return voxyDistance;
        try {
            return Math.max(voxyDistance, Api.INSTANCE.renderDistanceChunks());
        } catch (Throwable ignored) {
            return voxyDistance;
        }
    }

    /** Vulkan image-view of DH's own depth target. The Minecraft main depth does not contain LOD depth. */
    public static long depthTextureView() {
        if (!LOADED) return 0L;
        try {
            return RenderApi.INSTANCE.depthTextureView();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** Copy DH's exact inverse projection/model-view matrix into {@code dest}. */
    public static boolean inverseViewProjection(Matrix4f dest) {
        if (!LOADED) return false;
        try {
            return RenderApi.INSTANCE.inverseViewProjection(dest);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Isolated from the terrain API so a DH renderer-internal change cannot disable terrain capture. */
    private static final class RenderApi {
        static final RenderApi INSTANCE = new RenderApi();
        private final Field metaInstanceField;
        private final Field depthWrapperField;
        private final Method getTextureView;
        private final Field renderParamsField;
        private final Field validatedField;
        private final Field inverseMatrixField;
        private final Method matrixValues;
        private final Field renderBufferHandlerField;
        private final Field lodQuadTreeField;
        private final Method populateEnabledSections;
        private final Field lodRenderSectionPos;

        private RenderApi() {
            try {
                Class<?> meta = Class.forName("com.seibel.distanthorizons.common.render.blaze.BlazeDhMetaRenderer");
                metaInstanceField = meta.getField("INSTANCE");
                depthWrapperField = meta.getField("dhDepthTextureWrapper");
                Class<?> wrapper = Class.forName(
                        "com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper");
                getTextureView = wrapper.getMethod("getTextureView");

                Class<?> clientApi = Class.forName("com.seibel.distanthorizons.core.api.internal.ClientApi");
                renderParamsField = clientApi.getDeclaredField("RENDER_PARAMS");
                renderParamsField.setAccessible(true);
                Class<?> renderParams = Class.forName("com.seibel.distanthorizons.core.render.RenderParams");
                validatedField = renderParams.getField("hasBeenValidated");
                renderBufferHandlerField = renderParams.getField("renderBufferHandler");
                Class<?> handler = Class.forName("com.seibel.distanthorizons.core.render.RenderBufferHandler");
                lodQuadTreeField = handler.getField("lodQuadTree");
                Class<?> quadTree = Class.forName("com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree");
                populateEnabledSections = quadTree.getMethod("populateListWithEnabledRenderSections", ArrayList.class);
                Class<?> section = Class.forName("com.seibel.distanthorizons.core.render.QuadTree.LodRenderSection");
                lodRenderSectionPos = section.getField("pos");
                Class<?> apiParams = renderParams.getSuperclass();
                inverseMatrixField = apiParams.getField("dhInverseMvmProjectionMatrix");
                Class<?> matrix = Class.forName("com.seibel.distanthorizons.api.objects.math.DhApiMat4f");
                matrixValues = matrix.getMethod("getValuesAsArray");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Distant Horizons Blaze renderer", e);
            }
        }

        long depthTextureView() throws ReflectiveOperationException {
            Object meta = metaInstanceField.get(null);
            if (meta == null) return 0L;
            Object wrapper = depthWrapperField.get(meta);
            Object view = wrapper == null ? null : getTextureView.invoke(wrapper);
            return view instanceof VulkanGpuTextureView vkView ? vkView.vkImageView() : 0L;
        }

        boolean inverseViewProjection(Matrix4f dest) throws ReflectiveOperationException {
            Object params = renderParamsField.get(null);
            if (params == null || !validatedField.getBoolean(params)) return false;
            Object matrix = inverseMatrixField.get(params);
            float[] rowMajor = (float[]) matrixValues.invoke(matrix);
            // DhApiMat4f exposes row-major values; JOML/GLSL push data is column-major.
            dest.set(rowMajor).transpose();
            return dest.isFinite();
        }

        Set<Long> activeLodPositions() throws ReflectiveOperationException {
            Object params = renderParamsField.get(null);
            if (params == null || !validatedField.getBoolean(params)) return Set.of();
            Object handler = renderBufferHandlerField.get(params);
            if (handler == null) return Set.of();
            Object tree = lodQuadTreeField.get(handler);
            if (tree == null) return Set.of();
            ArrayList<Object> sections = new ArrayList<>();
            populateEnabledSections.invoke(tree, sections);
            HashSet<Long> result = new HashSet<>(Math.max(16, sections.size() * 2));
            for (Object section : sections) {
                if (section != null) result.add(lodRenderSectionPos.getLong(section));
            }
            return result;
        }
    }

    /** Public DH render-cache reload API, isolated so optional-version drift cannot disable capture. */
    private static final class ReloadApi {
        static final ReloadApi INSTANCE = new ReloadApi();
        private final Field renderProxyField;
        private final Method clearRenderDataCache;
        private final Field resultSuccess;

        private ReloadApi() {
            try {
                Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
                Class<?> renderProxy = Class.forName(
                        "com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy");
                Class<?> result = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult");
                renderProxyField = delayed.getField("renderProxy");
                clearRenderDataCache = renderProxy.getMethod("clearRenderDataCache");
                resultSuccess = result.getField("success");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Distant Horizons render reload API", e);
            }
        }

        boolean clearRenderDataCache() throws ReflectiveOperationException {
            Object proxy = renderProxyField.get(null);
            if (proxy == null) return false;
            Object result = clearRenderDataCache.invoke(proxy);
            return result != null && resultSuccess.getBoolean(result);
        }
    }

    /** Small reflective surface kept separate from renderer internals. */
    private static final class Api {
        static final Api INSTANCE = new Api();
        private final Field configsField;
        private final Method graphicsConfig;
        private final Method chunkRenderDistance;
        private final Method maxHorizontalResolution;
        private final Method horizontalQuality;
        private final Method configValue;
        private final Field dataPointWidth;
        private final Method minCornerX;
        private final Method minCornerZ;
        private final Method minHeight;
        private final Method blockWidth;

        private Api() {
            try {
                Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
                configsField = delayed.getField("configs");
                Class<?> level = Class.forName("com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
                Class<?> config = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig");
                Class<?> graphics = Class.forName(
                        "com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig");
                Class<?> value = Class.forName("com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
                graphicsConfig = config.getMethod("graphics");
                chunkRenderDistance = graphics.getMethod("chunkRenderDistance");
                maxHorizontalResolution = graphics.getMethod("maxHorizontalResolution");
                horizontalQuality = graphics.getMethod("horizontalQuality");
                configValue = value.getMethod("getValue");
                Class<?> maxResolution = Class.forName(
                        "com.seibel.distanthorizons.api.enums.config.EDhApiMaxHorizontalResolution");
                dataPointWidth = maxResolution.getField("dataPointWidth");
                Class<?> sectionPos = Class.forName("com.seibel.distanthorizons.core.pos.DhSectionPos");
                minCornerX = sectionPos.getMethod("getMinCornerBlockX", long.class);
                minCornerZ = sectionPos.getMethod("getMinCornerBlockZ", long.class);
                blockWidth = sectionPos.getMethod("getBlockWidth", long.class);
                minHeight = level.getMethod("getMinHeight");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unsupported Distant Horizons terrain API", e);
            }
        }

        int[] minCorner(long pos, Object levelHint) throws ReflectiveOperationException {
            int y = Integer.MIN_VALUE;
            if (levelHint != null) {
                try {
                    // Works for both IDhApiSinglePlayerLevelWrapper and IDhApiMultiplayerLevelWrapper.
                    y = ((Number) minHeight.invoke(levelHint)).intValue();
                } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
                    // Some DH releases pass a small internal client-level facade instead of the public API
                    // wrapper. Do not discard the whole remote-server VBO upload because of that detail.
                }
            }
            if (y == Integer.MIN_VALUE) {
                // The Minecraft ClientLevel exists for integrated and dedicated-server connections and is
                // therefore the authoritative fallback when DH's wrapper shape differs between versions.
                var clientLevel = Minecraft.getInstance().level;
                y = clientLevel != null ? clientLevel.getMinY() : -64;
            }
            return new int[]{((Number) minCornerX.invoke(null, pos)).intValue(), y,
                    ((Number) minCornerZ.invoke(null, pos)).intValue(),
                    ((Number) blockWidth.invoke(null, pos)).intValue()};
        }

        int renderDistanceChunks() throws ReflectiveOperationException {
            Object configs = configsField.get(null);
            if (configs == null) return 0;
            Object graphics = graphicsConfig.invoke(configs);
            Object distance = chunkRenderDistance.invoke(graphics);
            return ((Number) configValue.invoke(distance)).intValue();
        }


        LodQuality lodQuality() throws ReflectiveOperationException {
            Object configs = configsField.get(null);
            if (configs == null) return new LodQuality(0L, 16, 2, "UNKNOWN", "UNKNOWN");
            Object graphics = graphicsConfig.invoke(configs);
            Object maxValue = configValue.invoke(maxHorizontalResolution.invoke(graphics));
            Object horizontalValue = configValue.invoke(horizontalQuality.invoke(graphics));
            String maxName = maxValue instanceof Enum<?> value ? value.name() : String.valueOf(maxValue);
            String horizontalName = horizontalValue instanceof Enum<?> value
                    ? value.name() : String.valueOf(horizontalValue);
            int width = maxValue == null ? 16 : Math.max(1, dataPointWidth.getInt(maxValue));
            int rank = horizontalValue instanceof Enum<?> value ? value.ordinal() : 2;
            long signature = ((long) maxName.hashCode() << 32) ^ (horizontalName.hashCode() & 0xFFFFFFFFL);
            return new LodQuality(signature, width, rank, maxName, horizontalName);
        }
    }
}
