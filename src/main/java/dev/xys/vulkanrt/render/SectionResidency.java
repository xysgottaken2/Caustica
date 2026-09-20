package dev.xys.vulkanrt.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Pure diff of the captured section set. Planning never mutates/invalidates the committed scene. */
final class SectionResidency {
    record Diff<K>(List<K> reuse, List<K> build, List<K> retire) {
        boolean changed() { return !build.isEmpty() || !retire.isEmpty(); }
    }
    enum TlasAction { EMPTY, REUSE, UPDATE, BUILD }

    static <K, R, D> Diff<K> diff(Map<K, R> resident, Map<K, D> draws, BiPredicate<R, D> same) {
        var reuse = new ArrayList<K>();
        var build = new ArrayList<K>();
        var retire = new ArrayList<K>();
        draws.forEach((key, draw) -> {
            R old = resident.get(key);
            if (old != null && same.test(old, draw)) reuse.add(key);
            else {
                build.add(key);
                if (old != null) retire.add(key);
            }
        });
        resident.keySet().forEach(key -> { if (!draws.containsKey(key)) retire.add(key); });
        return new Diff<>(List.copyOf(reuse), List.copyOf(build), List.copyOf(retire));
    }
    /** Invoke only after successful enqueue. The callback schedules destruction, never frees in-flight AS. */
    static <K, R> int retireReplaced(Map<K, R> old, Map<K, R> next, Consumer<R> defer) {
        int retired = 0;
        if (old != next) for (var entry : old.entrySet()) {
            if (next.get(entry.getKey()) != entry.getValue()) {
                defer.accept(entry.getValue()); retired++;
            }
        }
        return retired;
    }
    static TlasAction tlasAction(int oldCount, int newCount, boolean changed, boolean rebase) {
        if (newCount == 0) return TlasAction.EMPTY;
        // Vulkan UPDATE requires the primitive/instance count of the original BUILD.
        if (oldCount != newCount) return TlasAction.BUILD;
        return changed || rebase ? TlasAction.UPDATE : TlasAction.REUSE;
    }
    private SectionResidency() {}
}
