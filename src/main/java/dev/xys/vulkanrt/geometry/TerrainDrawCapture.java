package dev.xys.vulkanrt.geometry;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.slf4j.LoggerFactory;
import java.util.Arrays;

/** Render-thread receipt of actual vanilla draw extraction; retains at most one GPU candidate.
 * The redirects forward both original calls unchanged. Copy/AS preparation happens at extraction
 * RETURN, before LevelRenderer's later compile/upload can replace or reuse this allocation. */
public final class TerrainDrawCapture {
    public record Draw(long frame, long section, SectionRenderDispatcher.RenderSection owner, SectionMesh mesh,
                       VulkanGpuBuffer buffer, long offset, SectionGeometryLayout layout) {}
    private static final boolean DIAGNOSTICS = Boolean.parseBoolean(System.getProperty("nativevulkanrt.chunkDiagnostics", "true"));
    private static final int[] rejected = new int[SectionGeometrySanity.Failure.values().length];
    private static long frame, selectionCamera = Long.MIN_VALUE, lastDiagnostic;
    private static Long pinned, preferred;
    private static LevelRenderer level;
    private static SectionRenderDispatcher dispatcher;
    private static VertexFormat format;
    private static boolean started, completed, sawPin;
    private static int sections, solidCalls, valid;
    private static double cameraX, cameraY, cameraZ, bestDistance;
    private static Draw selected;
    private static String firstRejected, configurationFailure;

    public static void beginFrame() {
        frame++; started = false; completed = false; level = null; dispatcher = null; selected = null;
        sections = 0; solidCalls = 0; valid = 0; sawPin = false; firstRejected = null;
        Arrays.fill(rejected, 0);
    }
    public static void begin(LevelRenderer owner, SectionRenderDispatcher source, double x, double y, double z) {
        started = true; completed = false; level = owner; dispatcher = source;
        selected = null; sections = 0; solidCalls = 0; valid = 0; sawPin = false; firstRejected = null;
        Arrays.fill(rejected, 0);
        cameraX = x; cameraY = y; cameraZ = z; bestDistance = Double.POSITIVE_INFINITY;
        long camera = SectionPos.asLong((int)Math.floor(x / 16), (int)Math.floor(y / 16), (int)Math.floor(z / 16));
        if (selectionCamera != camera) { selectionCamera = camera; preferred = null; }
        try { pinned = ChunkCoordinates.parseSection(System.getProperty("nativevulkanrt.section")); configurationFailure = null; }
        catch (IllegalArgumentException badOption) { configurationFailure = badOption.getMessage(); }
        format = ChunkSectionLayer.SOLID.pipeline(false).getVertexFormatBinding(0);
    }
    public static void sawSection() { sections++; }
    public static void observe(SectionRenderDispatcher source, SectionRenderDispatcher.RenderSection section,
                               SectionMesh mesh, ChunkSectionLayer layer, SectionRenderDispatcher.RenderSectionBufferSlice slice) {
        if (!started || source != dispatcher || layer != ChunkSectionLayer.SOLID) return;
        solidCalls++;
        long node = section == null ? 0 : section.getSectionNode();
        if (section != null && pinned != null && pinned == node) sawPin = true;
        var failure = SectionGeometrySanity.inspect(section, node, mesh, slice, format);
        if (failure != SectionGeometrySanity.Failure.OK) {
            rejected[failure.ordinal()]++;
            if (firstRejected == null || (pinned != null && pinned == node))
                firstRejected = describe(section, mesh, slice, node, failure);
            return;
        }
        valid++;
        if (configurationFailure != null || (pinned != null && pinned != node)) return;
        double dx = SectionPos.x(node) * 16.0 + 8 - cameraX, dy = SectionPos.y(node) * 16.0 + 8 - cameraY, dz = SectionPos.z(node) * 16.0 + 8 - cameraZ;
        double distance = dx * dx + dy * dy + dz * dz;
        if (prefer(node, distance, selected == null ? null : selected.section(), bestDistance, preferred)) {
            var draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
            selected = new Draw(frame, node, section, mesh, (VulkanGpuBuffer)slice.vertexBuffer(), slice.vertexBufferOffset(),
                    SectionGeometryLayout.solidQuads(format, draw.indexCount()));
            bestDistance = distance;
        }
    }
    /** Pure selection policy: all valid terrain draws are candidates; there is no 3x3x3 cutoff. */
    public static boolean prefer(long node, double distance, Long bestNode, double bestDistance, Long preferredNode) {
        if (bestNode == null) return true;
        if (preferredNode != null && bestNode.equals(preferredNode)) return false;
        return (preferredNode != null && node == preferredNode) || distance < bestDistance;
    }
    public static void finish(LevelRenderer owner) { if (level == owner) completed = true; }
    public static boolean ready(LevelRenderer owner, SectionRenderDispatcher source) { return started && completed && level == owner && source == dispatcher; }
    public static Draw selected() { return selected; }
    public static boolean current(Draw draw) { return draw != null && draw.frame() == frame && completed; }
    public static void preferSection(Long node) { preferred = node; }
    public static String failure() {
        if (!started) return "TERRAIN_EXTRACTION_HOOK_NOT_REACHED";
        if (!completed) return "TERRAIN_EXTRACTION_NOT_COMPLETED";
        if (configurationFailure != null) return "INVALID_SECTION_OPTION: " + configurationFailure;
        if (dispatcher == null) return "DISPATCHER_MISSING";
        if (sections == 0) return "NO_SECTIONS_IN_VANILLA_VISIBLE_LIST";
        if (solidCalls == 0) return "NO_SOLID_SLICE_CALLS_IN_TERRAIN_EXTRACTION";
        if (pinned != null && !sawPin) return "PIN_NOT_IN_CURRENT_TERRAIN_DRAWS";
        return "NO_ELIGIBLE_SOLID_DRAW (see rejection counts)";
    }
    public static boolean diagnosticsEnabled() { return DIAGNOSTICS; }
    public static void diagnostics(LevelRenderer owner, boolean force) {
        if (!DIAGNOSTICS || (!force && System.nanoTime() - lastDiagnostic < 5_000_000_000L)) return;
        lastDiagnostic = System.nanoTime();
        var log = LoggerFactory.getLogger("native_vulkan_rt");
        log.info("[RT][chunks-diag] stage=extractSectionDrawGroups frame={} started={} completed={} sections={} SOLID calls={} valid={} pinSeen={} camera=({}, {}, {}) cameraSection=({}, {}, {})",
                frame, started, completed, sections, solidCalls, valid, sawPin, cameraX, cameraY, cameraZ,
                (int)Math.floor(cameraX/16), (int)Math.floor(cameraY/16), (int)Math.floor(cameraZ/16));
        for (var failure : SectionGeometrySanity.Failure.values()) if (rejected[failure.ordinal()] > 0)
            log.info("[RT][chunks-diag] rejected {}: {}", failure, rejected[failure.ordinal()]);
        if (firstRejected != null) log.info("[RT][chunks-diag] rejected sample: {}", firstRejected);
        // Diagnostic ONLY. Never use a generic ViewArea lookup as geometry input to the BLAS.
        if (pinned != null && owner.viewArea() != null && owner.sectionRenderDispatcher() != null) {
            var d = owner.sectionRenderDispatcher(); d.lock();
            try {
                long node = pinned;
                var section = owner.viewArea().getRenderSectionAt(new BlockPos(SectionPos.x(node)*16,SectionPos.y(node)*16,SectionPos.z(node)*16));
                var mesh = section == null ? null : section.getSectionMesh();
                var slice = mesh == null ? null : d.getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID);
                var result = SectionGeometrySanity.inspect(section,node,mesh,slice,ChunkSectionLayer.SOLID.vertexFormat());
                log.info("[RT][chunks-diag] pinned lookup (diagnostic only): {}", describe(section,mesh,slice,node,result));
                if (!sawPin && result == SectionGeometrySanity.Failure.OK)
                    log.info("[RT][chunks-diag] Pin has an allocation, but was NOT used in this frame's terrain draws; check frustum/occlusion or test without the pin");
            } finally { d.unlock(); }
        }
    }
    public static String describe(SectionRenderDispatcher.RenderSection section, SectionMesh mesh,
                                  SectionRenderDispatcher.RenderSectionBufferSlice slice, long node, SectionGeometrySanity.Failure result) {
        var draw = mesh == null ? null : mesh.getSectionDraw(ChunkSectionLayer.SOLID);
        long indices = draw == null ? 0 : draw.indexCount(), vertices = indices / 6 * 4;
        int stride = ChunkSectionLayer.SOLID.vertexFormat().getVertexSize();
        long offset = slice == null ? -1 : slice.vertexBufferOffset();
        var buffer = slice == null ? null : slice.vertexBuffer();
        return "section=("+SectionPos.x(node)+","+SectionPos.y(node)+","+SectionPos.z(node)+") actualNode="
                +(section == null ? "none" : "("+SectionPos.x(section.getSectionNode())+","+SectionPos.y(section.getSectionNode())+","+SectionPos.z(section.getSectionNode())+")")+" owner="+SectionGeometrySanity.identity(section)
                +" mesh="+SectionGeometrySanity.identity(mesh)+" state="+SectionGeometrySanity.meshState(mesh)
                +" SOLID vertices="+vertices+" indices="+indices+" triangles="+(indices/3)+" stride="+stride
                +" buffer="+SectionGeometrySanity.identity(buffer)+" handle="+(buffer instanceof VulkanGpuBuffer vk ? "0x"+Long.toHexString(vk.vkBuffer()) : "none")
                +" offset="+offset+" bytes="+(vertices*stride)+" bufferSize="+(buffer == null ? 0 : buffer.size())+" sanity="+result;
    }
    private TerrainDrawCapture() {}
}
