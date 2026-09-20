package dev.xys.vulkanrt.render;

import org.slf4j.LoggerFactory;

/** Metadata only. Never reads an image or a vertex back from the GPU. Logs only on resize/change. */
public final class TextureQualityDiagnostics {
    record Extents(int rtW, int rtH, int targetW, int targetH, int imageW, int imageH, int windowW, int windowH) {
        boolean blitScales() { return rtW != imageW || rtH != imageH; }
        boolean windowMatches() { return rtW == windowW && rtH == windowH; }
    }
    private static Extents last;
    public static void output(int rw, int rh, int tw, int th, int iw, int ih, int ww, int wh) {
        if (last != null && last.rtW==rw && last.rtH==rh && last.targetW==tw && last.targetH==th
                && last.imageW==iw && last.imageH==ih && last.windowW==ww && last.windowH==wh) return;
        last = new Extents(rw,rh,tw,th,iw,ih,ww,wh);
        var log=LoggerFactory.getLogger("native_vulkan_rt");
        log.info("[RT][texture-quality] dispatch={}x{} RT image={}x{} mainTarget={}x{} destination mip0={}x{} window framebuffer={}x{}; blitScales={} blitFilter=NEAREST; RT atlas copy/resize=NO",
                rw,rh,rw,rh,tw,th,iw,ih,ww,wh,last.blitScales());
        if (last.blitScales() || !last.windowMatches())
            log.warn("[RT][texture-quality] RESOLUTION_MISMATCH: inspect the dimensions above; this is independent of atlas sampling");
    }
    private TextureQualityDiagnostics() {}
}
