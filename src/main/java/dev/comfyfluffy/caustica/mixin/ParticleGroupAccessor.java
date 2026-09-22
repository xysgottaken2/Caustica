package dev.comfyfluffy.caustica.mixin;

import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a particle group's live queue so the RT renderer can iterate the {@code Particle} objects for
 * per-particle motion vectors (see {@link ParticleEngineAccessor}).
 */
@Mixin(ParticleGroup.class)
public interface ParticleGroupAccessor {
    @Accessor("particles")
    Queue<? extends Particle> caustica$getParticles();
}
