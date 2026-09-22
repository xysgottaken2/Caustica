// Shadow / sky-visibility miss (SBT miss index 1). Note this shader does not actually RUN in the
// current pipeline: visibility() uses a hit object purely as the traversal result and never invokes
// the miss shader — it only fills the SBT slot.
// Radiance and shadow rays share the exact Payload ABI, as Vulkan requires for every stage reachable by
// a trace — see world.rgen.slang's visibility().
import world_common;

[shader("miss")]
void main(inout Payload payload) {
    payload.hitT = 1.0;
}
