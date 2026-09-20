package dev.xys.vulkanrt.geometry;

import dev.xys.vulkanrt.render.RtOptions;
import java.util.*;

/** Worker → render handoff, bounded by sections and CPU bytes; no GPU operations on workers.
 * Epochs reject compilations started before world/reload reset. Ownership rejects stale unloads. */
public final class GeometryInbox {
    public record Capture(long epoch, long section, TriangleMesh mesh) {}
    public record Entry(Object owner, Capture capture) {}
    public record Snapshot(long revision, long epoch, Map<Long, Entry> sections) {}
    private static final long CPU_BUDGET = 64L * 1024 * 1024;
    private static final Map<Object, Capture> COMPILED = new WeakHashMap<>();
    private static final LinkedHashMap<Long, Entry> RESIDENT = new LinkedHashMap<>();
    private static long epoch, revision, bytes;

    public static synchronized long epoch() { return epoch; }
    public static synchronized long revision() { return revision; }
    public static synchronized void attachResult(Object results, Capture capture) { COMPILED.put(results, capture); }
    public static synchronized Capture takeResult(Object results) { return COMPILED.remove(results); }
    public static synchronized void reset() { epoch++; revision++; RESIDENT.clear(); COMPILED.clear(); bytes = 0; }

    public static synchronized void publish(Object owner, long section, Capture capture) {
        if (capture == null) { remove(owner, section); return; }
        if (capture.epoch() != epoch || capture.section() != section) return;
        if (capture.mesh() == null) { remove(owner, section); return; }
        Entry old = RESIDENT.remove(section);
        if (old != null) bytes -= old.capture().mesh().bytes();
        RESIDENT.put(section, new Entry(owner, capture)); bytes += capture.mesh().bytes();
        while (RESIDENT.size() > RtOptions.MAX_SECTIONS || bytes > CPU_BUDGET) {
            Long first = RESIDENT.keySet().iterator().next();
            bytes -= RESIDENT.remove(first).capture().mesh().bytes();
        }
        revision++;
    }
    public static synchronized void remove(Object owner, long section) {
        Entry existing = RESIDENT.get(section);
        if (existing != null && existing.owner() == owner) {
            bytes -= RESIDENT.remove(section).capture().mesh().bytes(); revision++;
        }
    }
    public static synchronized Snapshot snapshot() { return new Snapshot(revision, epoch, Map.copyOf(RESIDENT)); }
    private GeometryInbox() {}
}
