package dev.xys.vulkanrt.render;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.ArrayList;
import java.util.List;

/** Rollback is allowed only BEFORE enqueue. Retires transient allocations using vanilla completion. */
public final class CommandBatch implements AutoCloseable {
    private final VulkanRayTracingContext context;
    private final List<AutoCloseable> rollback = new ArrayList<>();
    private final List<AutoCloseable> temporary = new ArrayList<>();
    public final VkCommandBuffer commands;
    private boolean enqueued;

    public CommandBatch(VulkanRayTracingContext context) { this.context = context; commands = context.beginCommands(); }
    public <T extends AutoCloseable> T own(T resource) { rollback.add(resource); return resource; }
    public <T extends AutoCloseable> T temporary(T resource) { own(resource); temporary.add(resource); return resource; }
    public void commit() {
        if (enqueued) throw new IllegalStateException("Batch already enqueued");
        context.enqueue(commands);
        enqueued = true;
        temporary.forEach(context::retire);
    }
    @Override public void close() {
        if (!enqueued) {
            for (int i = rollback.size() - 1; i >= 0; i--) {
                try { rollback.get(i).close(); }
                catch (Exception failure) { org.slf4j.LoggerFactory.getLogger("native_vulkan_rt").error("[RT] Cleanup", failure); }
            }
        }
    }
}
