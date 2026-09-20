package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/** Exercises the production descriptor builder with actual LWJGL structs. No Vulkan calls/GPU.
 * Regression: pGeometries does NOT fill geometryCount in LWJGL 3.4.3. */
final class AccelerationStructureBuildInfoTest {
    @Test void pointerSetterAloneLeavesAnEmptyBuild() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default();
            var broken = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default().pGeometries(geometry);
            assertEquals(0, broken.geometryCount(), "This is the old empty-AS bug, not the intended count");
        }
    }

    @Test void triangleBlasHasOneGeometryInSizeQueryAndBuildDescriptor() {
        verify(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, VK_GEOMETRY_TYPE_TRIANGLES_KHR, false, false);
    }

    @Test void instanceTlasHasOneGeometryInSizeQueryAndBuildDescriptor() {
        verify(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, VK_GEOMETRY_TYPE_INSTANCES_KHR, false, true);
    }

    @Test void tlasUpdateAlsoRetainsOneGeometry() {
        verify(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, VK_GEOMETRY_TYPE_INSTANCES_KHR, true, true);
    }

    private static void verify(int type, int geometryType, boolean update, boolean allowUpdate) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack).sType$Default().geometryType(geometryType);
            var info = AccelerationStructureManager.singleGeometryBuildInfo(stack, geometry, type, update, allowUpdate);
            // The size query consumes get(0); the GPU build consumes the struct buffer.
            assertEquals(1, info.remaining());
            assertEquals(1, info.geometryCount());
            assertEquals(1, info.get(0).geometryCount());
            assertEquals(1, info.pGeometries().remaining());
            assertEquals(geometry.address(), info.pGeometries().address());
            assertEquals(geometryType, info.pGeometries().geometryType());
            assertNull(info.ppGeometries(), "Use one representation, not both pGeometries and ppGeometries");
            assertEquals(type, info.type());
            assertEquals(update ? VK_BUILD_ACCELERATION_STRUCTURE_MODE_UPDATE_KHR : VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR, info.mode());
            assertEquals(allowUpdate, (info.flags() & VK_BUILD_ACCELERATION_STRUCTURE_ALLOW_UPDATE_BIT_KHR) != 0);
        }
    }

    @Test void rangeAndGeometryCountsCannotSilentlyDiverge() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var empty = VkAccelerationStructureGeometryKHR.calloc(0, stack);
            var two = VkAccelerationStructureGeometryKHR.calloc(2, stack);
            var one = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            assertThrows(IllegalArgumentException.class, () -> AccelerationStructureManager.singleGeometryBuildInfo(
                    stack, empty, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, false, false));
            assertThrows(IllegalArgumentException.class, () -> AccelerationStructureManager.singleGeometryBuildInfo(
                    stack, two, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR, false, false));
            assertThrows(IllegalArgumentException.class, () -> AccelerationStructureManager.singleGeometryBuildInfo(
                    stack, one, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR, true, false));
        }
    }
}
