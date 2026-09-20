package dev.xys.vulkanrt.geometry;

import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ChunkCoordinatesTest {
    @Test void sectionTranslationAndCameraMatchVanillaEvenAtNegativeAndFarCoordinates() {
        for (double[] camera : new double[][]{{7.98,72,7.7}, {-0.25, -12, -256.1}, {29999980.25, 74.5, -29999980.75}}) {
            double x = camera[0], y = camera[1], z = camera[2];
            long node = SectionPos.asLong((int)Math.floor(x/16), (int)Math.floor(y/16), (int)Math.floor(z/16));
            var anchor = ChunkCoordinates.Anchor.near(x,y,z);
            assertEquals(SectionPos.x(node)*16.0+3.25-x, 3.25f+anchor.sectionX(node)-anchor.cameraX(x), 0.0001);
            assertEquals(SectionPos.y(node)*16.0+2.5-y, 2.5f+anchor.sectionY(node)-anchor.cameraY(y), 0.0001);
            assertEquals(SectionPos.z(node)*16.0+5-z, 5f+anchor.sectionZ(node)-anchor.cameraZ(z), 0.0001);
        }
    }
    @Test void anchorUsesFloorNotTruncationAndRebaseDoesNotChangeRelativePosition() {
        var a = ChunkCoordinates.Anchor.near(-0.1,255.9,-256.1);
        assertEquals(-256,a.x()); assertEquals(0,a.y()); assertEquals(-512,a.z());
        long node = SectionPos.asLong(16,4,0);
        var before = ChunkCoordinates.Anchor.near(255.9,70,5);
        var after = ChunkCoordinates.Anchor.near(256.1,70,5);
        assertEquals(0.2, (before.sectionX(node)-before.cameraX(255.9))-(after.sectionX(node)-after.cameraX(256.1)), 0.0001);
    }
    @Test void unprojectionMatchesRealViewRotationAndReversedDepthProjection() {
        var projection = new Matrix4f().setPerspective((float)Math.toRadians(70), 854f/480, 1024, 0.05f, true);
        var rotation = new Matrix4f().rotateX(0.25f).rotateY(-0.8f);
        double x = -29999980.25, y = 71.75, z = 29999980.5;
        var anchor = ChunkCoordinates.Anchor.near(x,y,z);
        var inv = ChunkCoordinates.inverse(new Matrix4f(),projection,rotation,anchor,x,y,z);
        var p = new Vector4f(0,0,0.5f,1).mul(inv);
        var ray = new Vector3f(p.x/p.w-anchor.cameraX(x),p.y/p.w-anchor.cameraY(y),p.z/p.w-anchor.cameraZ(z)).normalize();
        var expected = new Vector3f(0,0,-1).mulDirection(new Matrix4f(rotation).invert()).normalize();
        assertEquals(expected.x,ray.x,0.0003); assertEquals(expected.y,ray.y,0.0003); assertEquals(expected.z,ray.z,0.0003);
        // Vulkan's actual viewport has positive height. Pixel NDC is unprojected without an extra Y flip.
        var corner = new Vector4f(0.4f,-0.3f,0.5f,1).mul(inv);
        var roundTrip = new Matrix4f(projection).mul(rotation)
                .translate(-anchor.cameraX(x),-anchor.cameraY(y),-anchor.cameraZ(z)).transform(corner);
        assertEquals(0.4,roundTrip.x/roundTrip.w,0.001); assertEquals(-0.3,roundTrip.y/roundTrip.w,0.001);
    }
    @Test void explicitSelectionIsInSectionUnitsAndCannotWrap() {
        assertNull(ChunkCoordinates.parseSection(null));
        long node = ChunkCoordinates.parseSection("-1, 4, 0");
        assertEquals(-1, SectionPos.x(node)); assertEquals(4,SectionPos.y(node)); assertEquals(0,SectionPos.z(node));
        assertThrows(IllegalArgumentException.class, () -> ChunkCoordinates.parseSection("1,2"));
        assertThrows(IllegalArgumentException.class, () -> ChunkCoordinates.parseSection("2147483647,0,0"));
    }
}
