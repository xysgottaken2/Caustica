package dev.xys.vulkanrt.geometry;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.Collections;

/** Render-thread receipt of actual vanilla draw extraction; retains every eligible SOLID section, deduplicated by packed section position.
 * The redirects forward both original calls unchanged. Copy/AS preparation happens at extraction
 * RETURN, before LevelRenderer's later compile/upload can replace or reuse this allocation. */
public final class TerrainDrawCapture {
    public record Draw(long frame, long section, SectionRenderDispatcher.RenderSection owner, SectionMesh mesh,
                       VulkanGpuBuffer buffer, long offset, SectionGeometryLayout layout) {}
    private static final boolean DIAGNOSTICS = Boolean.parseBoolean(System.getProperty("nativevulkanrt.chunkDiagnostics", "true"));
    private static final int[] rejected = new int[SectionGeometrySanity.Failure.values().length];
    private static long frame, lastDiagnostic;
    private static Long pinned;
    private static LevelRenderer level;
    private static SectionRenderDispatcher dispatcher;
    private static VertexFormat format;
    private static boolean started, completed, sawPin;
    private static int sections, solidCalls, valid;
    private static double cameraX, cameraY, cameraZ;
    private static Map<Long, Draw> draws = new TreeMap<>(), previous = Map.of();
    private static VertexFormat previousFormat;
    private static Map<Long, Draw> cutouts = new TreeMap<>(), previousCutouts = Map.of();
    private static String firstRejected, configurationFailure, firstCutoutRejected;
    private static int cutoutCalls, cutoutRejected;
    private static VertexFormat cutoutFormat,previousCutoutFormat;

    public static void beginFrame() {
        frame++; started = false; completed = false; level = null; dispatcher = null;
        previousCutoutFormat=cutoutFormat;
        previousCutouts=cutouts; cutouts=new TreeMap<>();
        previous = draws; draws = new TreeMap<>(); previousFormat = format;
        cutoutCalls=0;cutoutRejected=0;firstCutoutRejected=null;
        sections = 0; solidCalls = 0; valid = 0; sawPin = false; firstRejected = null;
        Arrays.fill(rejected, 0);
    }
    public static void begin(LevelRenderer owner, SectionRenderDispatcher source, double x, double y, double z) {
        started = true; completed = false; level = owner; dispatcher = source;
        cutoutCalls=0;cutoutRejected=0;firstCutoutRejected=null;
        cutouts.clear(); draws.clear(); sections = 0; solidCalls = 0; valid = 0; sawPin = false; firstRejected = null;
        Arrays.fill(rejected, 0);
        cameraX = x; cameraY = y; cameraZ = z;
        try { pinned = ChunkCoordinates.parseSection(System.getProperty("nativevulkanrt.section")); configurationFailure = null; }
        catch (IllegalArgumentException badOption) { configurationFailure = badOption.getMessage(); }
        format = ChunkSectionLayer.SOLID.pipeline(false).getVertexFormatBinding(0);
        cutoutFormat=ChunkSectionLayer.CUTOUT.pipeline(false).getVertexFormatBinding(0);
    }
    public static void sawSection() { sections++; }
    public static void observe(SectionRenderDispatcher source, SectionRenderDispatcher.RenderSection section,
                               SectionMesh mesh, ChunkSectionLayer layer, SectionRenderDispatcher.RenderSectionBufferSlice slice) {
        if (!started || source != dispatcher) return;
        if (layer == ChunkSectionLayer.CUTOUT) {
            long node=section == null ? 0 : section.getSectionNode();
            cutoutCalls++;
            var failure=SectionGeometrySanity.inspect(section,node,mesh,slice,cutoutFormat,layer);
            if(failure!=SectionGeometrySanity.Failure.OK) {
                cutoutRejected++;
                if(firstCutoutRejected==null) firstCutoutRejected="section="+SectionPos.x(node)+","+SectionPos.y(node)+","+SectionPos.z(node)+" reason="+failure;
                return;
            }
            if (configurationFailure == null && (pinned == null || pinned == node)) {
                var draw=mesh.getSectionDraw(layer);
                var old=previousCutouts.get(node);
                var layout=old != null && previousCutoutFormat==cutoutFormat && old.mesh()==mesh && old.layout().indexCount()==draw.indexCount()
                        ? old.layout() : SectionGeometryLayout.solidQuads(cutoutFormat,draw.indexCount());
                cutouts.put(node,new Draw(frame,node,section,mesh,(VulkanGpuBuffer)slice.vertexBuffer(),slice.vertexBufferOffset(),layout));
            }
            return;
        }
        if (layer != ChunkSectionLayer.SOLID) return;
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
        var draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
        var old = previous.get(node);
        // Reuse immutable format metadata; never read vertices back or retain a receipt as current.
        var layout = old != null && previousFormat == format && old.mesh() == mesh
                && old.layout().indexCount() == draw.indexCount()
                ? old.layout() : SectionGeometryLayout.solidQuads(format, draw.indexCount());
        retain(draws, new Draw(frame, node, section, mesh, (VulkanGpuBuffer)slice.vertexBuffer(),
                slice.vertexBufferOffset(), layout), pinned);
    }
    /** No radius/nearest filter. Duplicate observations cannot become duplicate TLAS instances. */
    static void retain(Map<Long, Draw> into, Draw draw, Long pin) {
        if (pin == null || pin == draw.section()) into.put(draw.section(), draw);
    }
    public static void finish(LevelRenderer owner) { if (level == owner) completed = true; }
    public static boolean ready(LevelRenderer owner, SectionRenderDispatcher source) { return started && completed && level == owner && source == dispatcher; }
    public static Map<Long, Draw> draws() { return Collections.unmodifiableMap(draws); }
    public static Draw cutout(long section) { return cutouts.get(section); }
    public static int validSections() { return draws.size(); }
    public static boolean current(Draw draw) { return draw != null && draw.frame() == frame && completed; }
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
        log.info("[RT][chunks-diag] stage=extractSectionDrawGroups frame={} started={} completed={} sections={} SOLID calls={} valid={} validSections={} pinSeen={} camera=({}, {}, {}) cameraSection=({}, {}, {})",
                frame, started, completed, sections, solidCalls, valid, draws.size(), sawPin, cameraX, cameraY, cameraZ,
                (int)Math.floor(cameraX/16), (int)Math.floor(cameraY/16), (int)Math.floor(cameraZ/16));
        log.info("[RT][coplanar-diag] CUTOUT calls={} accepted={} rejected={} firstRejection={}; auxiliary shading data only, not AS geometry",cutoutCalls,cutouts.size(),cutoutRejected,firstCutoutRejected);
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
