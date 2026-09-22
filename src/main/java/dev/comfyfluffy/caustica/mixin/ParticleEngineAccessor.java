package dev.comfyfluffy.caustica.mixin;

import java.util.Map;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the live particle groups so the RT renderer can iterate the actual {@code Particle} objects
 * (which carry stable identity + previous-tick position) for per-particle motion vectors — the packed
 * {@code ParticlesRenderState} the public extract path produces has no per-particle identity.
 */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("particles")
    Map<ParticleRenderType, ParticleGroup<?>> caustica$getParticleGroups();
}
