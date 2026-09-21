package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** CPU reference cases for the association/compositing contract, NOT a GPU execution test. */
final class CoplanarOverlayContractTest {
    private static final float[][] SIDE={{0,0,0},{0,1,0},{1,1,0},{1,0,0}};
    private static int[] permutation(float[][] solid,float[][] cutout) {
        int used=0; var result=new int[]{-1,-1,-1,-1};
        for(int i=0;i<4;i++) for(int j=0;j<4;j++) {
            boolean equal=true;
            for(int k=0;k<3;k++) equal &= Math.abs(solid[i][k]-cutout[j][k])<0.00001f;
            if(equal && (used&(1<<j))==0) { result[i]=j;used|=1<<j;break; }
        }
        var a=normal(solid);var b=normal(cutout);
        float dot=a[0]*b[0]+a[1]*b[1]+a[2]*b[2];
        return used==15 && dot>0 ? result : null;
    }
    private static float[] normal(float[][] quad) {
        float[] a=new float[3],b=new float[3];
        for(int i=0;i<3;i++) { a[i]=quad[1][i]-quad[0][i]; b[i]=quad[2][i]-quad[0][i]; }
        return new float[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }
    @Test void matchesAllFourCornersAndPreservesPermutationForUvAndColor() {
        assertArrayEquals(new int[]{0,1,2,3},permutation(SIDE,SIDE));
        var rotated=new float[][]{SIDE[2],SIDE[3],SIDE[0],SIDE[1]};
        assertArrayEquals(new int[]{2,3,0,1},permutation(SIDE,rotated));
    }
    @Test void rejectsOppositeNeighborFaceAndNonCoincidentVegetation() {
        assertNull(permutation(SIDE,new float[][]{SIDE[3],SIDE[2],SIDE[1],SIDE[0]}));
        var shifted=new float[4][3];
        for(int i=0;i<4;i++) { shifted[i]=SIDE[i].clone(); shifted[i][2]+=0.5f; }
        assertNull(permutation(SIDE,shifted));
        assertNull(permutation(SIDE,new float[][]{SIDE[0],SIDE[0],SIDE[1],SIDE[2]}));
    }
    @Test void cutoffIsAfterVertexAlphaAndDoesNotTintDirtOrAlphaBlend() {
        // Synthetic colors; deliberately NOT labeled measured Plains/Savanna values.
        float[] dirt={0.3f,0.2f,0.1f},whiteGrass={0.7f,0.7f,0.7f},tintA={0.2f,0.8f,0.1f},tintB={0.7f,0.7f,0.2f};
        assertArrayEquals(dirt,composite(dirt,whiteGrass,tintA,0,1));
        assertArrayEquals(dirt,composite(dirt,whiteGrass,tintB,0.49f,1));
        assertArrayEquals(dirt,composite(dirt,whiteGrass,tintA,1,0.49f));
        assertArrayEquals(new float[]{0.14f,0.56f,0.07f},composite(dirt,whiteGrass,tintA,0.5f,1),0.000001f);
        assertArrayEquals(new float[]{0.49f,0.49f,0.14f},composite(dirt,whiteGrass,tintB,1,1),0.000001f);
    }
    private static float[] composite(float[] base,float[] sample,float[] tint,float sampleAlpha,float vertexAlpha) {
        return sampleAlpha*vertexAlpha>=0.5f ? new float[]{sample[0]*tint[0],sample[1]*tint[1],sample[2]*tint[2]} : base;
    }
    @Test void hashCapacityIsBoundedPowerOfTwoWithAtMostHalfInitialLoad() {
        for(int quads:new int[]{1,31,32,33,1024,74898}) {
            int count=CoplanarOverlayMapper.bucketCount(quads);
            assertEquals(0,count&(count-1));assertTrue(count>=64);assertTrue(count>=2*quads);
        }
        assertThrows(IllegalArgumentException.class,()->CoplanarOverlayMapper.bucketCount(0));
        assertThrows(IllegalArgumentException.class,()->CoplanarOverlayMapper.bucketCount(Integer.MAX_VALUE));
    }
}
