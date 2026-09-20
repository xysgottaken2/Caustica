package dev.xys.vulkanrt.build

/** Structural guards for the exact upload/emission/draw interception sites. No GPU-runtime claim. */
final class EntityHookContract {
    static final String QUAD = 'com/mojang/blaze3d/vertex/VertexConsumer.putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V'
    static final String COPY = 'com/mojang/renderpearl/api/commands/CommandEncoder.copyToBuffer(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V'
    static final Map<String,Map<String,Map<String,Integer>>> EXPECTED = [
        'net/minecraft/client/renderer/StagedVertexBuffer': ['uploadDrawsToBuffers': [(COPY):2]],
        'net/minecraft/client/renderer/feature/ItemFeatureRenderer': ['prepareMainSubmit': [(QUAD):1]],
        'net/minecraft/client/renderer/feature/MovingBlockFeatureRenderer': [
            'putBakedQuad': [(QUAD):1],
            'buildGroup': ['net/minecraft/client/renderer/feature/MovingBlockFeatureRenderer$Submit.movingBlockRenderState()Lnet/minecraft/client/renderer/block/MovingBlockRenderState;':1]],
        'net/minecraft/client/model/geom/ModelPart': ['compile': ['net/minecraft/client/model/geom/ModelPart$Cube.compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V':1]],
        'net/minecraft/client/renderer/rendertype/PreparedRenderType': ['draw': ['com/mojang/renderpearl/api/commands/RenderPass.drawIndexed(IIIII)V':1]]
    ]
    static List<String> verify(String owner,byte[] bytes) { return verifyCalls(owner,TerrainHookContract.calls(bytes)) }
    static List<String> verifyCalls(String owner,Map<String,List<String>> methods) {
        List<String> errors=[]
        EXPECTED.getOrDefault(owner,[:]).each { name, sites ->
            def matches=methods.findAll { key,value -> key.startsWith(name+'(') }
            if(matches.size()!=1) errors.add("Entity hook: ${owner}.${name} must have exactly one implementation".toString())
            else sites.each { target,count ->
                if(matches.values().first().count(target)!=count)
                    errors.add("Entity hook: ${owner}.${name} expected ${count} calls to ${target}".toString())
            }
        }
        return errors
    }
}
