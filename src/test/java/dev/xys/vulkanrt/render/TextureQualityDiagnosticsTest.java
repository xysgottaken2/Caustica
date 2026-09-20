package dev.xys.vulkanrt.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class TextureQualityDiagnosticsTest {
    @Test void fullResolutionBlitIsNotAnUpscale() {
        var dims=new TextureQualityDiagnostics.Extents(1920,1080,1920,1080,1920,1080,1920,1080);
        assertFalse(dims.blitScales()); assertTrue(dims.windowMatches());
    }
    @Test void distinguishesLowResolutionTargetFromBlitScaling() {
        var lowTarget=new TextureQualityDiagnostics.Extents(256,144,256,144,256,144,1920,1080);
        assertFalse(lowTarget.blitScales()); assertFalse(lowTarget.windowMatches());
        var scaledBlit=new TextureQualityDiagnostics.Extents(256,144,1920,1080,1920,1080,1920,1080);
        assertTrue(scaledBlit.blitScales()); assertFalse(scaledBlit.windowMatches());
    }
    @Test void samplingOptionIsExplicitAndRejectsTypos() {
        assertEquals(ChunkTextureSampling.TEXEL,ChunkTextureSampling.parse("texel"));
        assertEquals(ChunkTextureSampling.LINEAR,ChunkTextureSampling.parse(" LINEAR "));
        assertThrows(IllegalArgumentException.class,()->ChunkTextureSampling.parse("upscale"));
        assertThrows(IllegalArgumentException.class,()->ChunkTextureSampling.parse(""));
    }
}
