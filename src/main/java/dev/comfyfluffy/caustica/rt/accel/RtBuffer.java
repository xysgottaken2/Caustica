package dev.comfyfluffy.caustica.rt.accel;

import dev.comfyfluffy.caustica.rt.VulkanDiagnostics;
import org.lwjgl.util.vma.Vma;

/**
 * A VMA-backed Vulkan buffer with a device address (for RT geometry, scratch, SBT, etc.).
 * Created via {@link dev.comfyfluffy.caustica.rt.RtContext#createBuffer}; freed with {@link #destroy()}.
 */
public final class RtBuffer {
    public final long handle;
    public final long allocation;
    public final long deviceAddress;
    /** Host pointer if created host-visible, else 0. */
    public final long mapped;
    /** Allocated capacity in bytes. */
    public final long size;
    /** Original usage flags passed to {@code createBuffer} (pre {@code SHADER_DEVICE_ADDRESS}). */
    public final int usage;
    /** Whether this buffer is host-visible+mapped. */
    public final boolean hostVisible;

    private final long vma;
    private boolean destroyed;

    public RtBuffer(long vma, long handle, long allocation, long deviceAddress, long mapped, long size, int usage,
                    boolean hostVisible, String label) {
        this.vma = vma;
        this.handle = handle;
        this.allocation = allocation;
        this.deviceAddress = deviceAddress;
        this.mapped = mapped;
        this.size = size;
        this.usage = usage;
        this.hostVisible = hostVisible;
        VulkanDiagnostics.registerBuffer(deviceAddress, size, handle, label);
    }

    public void destroy() {
        if (!destroyed && handle != 0L) {
            VulkanDiagnostics.unregisterBuffer(deviceAddress, handle);
            Vma.vmaDestroyBuffer(vma, handle, allocation);
            destroyed = true;
        }
    }

    /** Flush host writes for non-coherent allocations; VMA treats coherent memory as a no-op. */
    public void flush() {
        flush(0L, size);
    }

    /** Flush a written byte range; VMA handles non-coherent atom alignment internally. */
    public void flush(long offset, long length) {
        if (!hostVisible) {
            throw new IllegalStateException("Cannot flush a non-host-visible buffer");
        }
        if (offset < 0L || length < 0L || offset > size || length > size - offset) {
            throw new IndexOutOfBoundsException("Flush range " + offset + ".." + (offset + length)
                    + " exceeds buffer size " + size);
        }
        Vma.vmaFlushAllocation(vma, allocation, offset, length);
    }
}
