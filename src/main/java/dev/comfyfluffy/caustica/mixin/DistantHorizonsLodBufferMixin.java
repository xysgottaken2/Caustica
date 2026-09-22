package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.compat.DistantHorizonsCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer", remap = false)
public abstract class DistantHorizonsLodBufferMixin {
    @Inject(method = "tryMakeAndUploadBuffersAsync", at = @At("HEAD"), require = 0)
    private static void caustica$capture(long pos, @Coerce Object level, ArrayList<ByteBuffer> opaque,
                                         ArrayList<ByteBuffer> transparent,
                                         CallbackInfoReturnable<CompletableFuture<?>> cir) {
        DistantHorizonsCompat.captureLodBuffers(pos, level, opaque, transparent);
    }
}
