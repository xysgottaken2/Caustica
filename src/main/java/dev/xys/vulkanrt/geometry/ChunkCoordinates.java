package dev.xys.vulkanrt.geometry;

import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Mirrors terrain.vsh: Position + sectionWorldOrigin - cameraWorldPosition, then view rotation.
 * Doubles are subtracted BEFORE casting to float, including negative coordinates and far worlds. */
public final class ChunkCoordinates {
    public record Anchor(double x, double y, double z) {
        public static Anchor near(double x, double y, double z) {
            return new Anchor(Math.floor(x / 256) * 256, Math.floor(y / 256) * 256, Math.floor(z / 256) * 256);
        }
        public float cameraX(double x) { return (float)(x - this.x); }
        public float cameraY(double y) { return (float)(y - this.y); }
        public float cameraZ(double z) { return (float)(z - this.z); }
        public float sectionX(long node) { return (float)(SectionPos.x(node) * 16.0 - x); }
        public float sectionY(long node) { return (float)(SectionPos.y(node) * 16.0 - y); }
        public float sectionZ(long node) { return (float)(SectionPos.z(node) * 16.0 - z); }
    }
    public static Matrix4f inverse(Matrix4f destination, Matrix4fc projection, Matrix4fc viewRotation,
                                   Anchor anchor, double x, double y, double z) {
        return destination.set(projection).mul(viewRotation)
                .translate(-anchor.cameraX(x), -anchor.cameraY(y), -anchor.cameraZ(z)).invert();
    }
    /** Optional exact section coordinates, NOT block coordinates. Null selects a nearby accepted mesh. */
    public static Long parseSection(String value) {
        if (value == null || value.isBlank()) return null;
        String[] xyz = value.split(",", -1);
        if (xyz.length != 3) throw new IllegalArgumentException("nativevulkanrt.section must be section coordinates x,y,z");
        int x = Integer.parseInt(xyz[0].trim()), y = Integer.parseInt(xyz[1].trim()), z = Integer.parseInt(xyz[2].trim());
        long packed = SectionPos.asLong(x, y, z);
        if (SectionPos.x(packed) != x || SectionPos.y(packed) != y || SectionPos.z(packed) != z)
            throw new IllegalArgumentException("Section coordinates exceed Minecraft's packed range");
        return packed;
    }
    private ChunkCoordinates() {}
}
