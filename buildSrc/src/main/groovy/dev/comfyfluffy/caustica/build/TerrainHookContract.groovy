package dev.comfyfluffy.caustica.build

import org.objectweb.asm.*

/** Bytecode call-site guards, not proof of Mixin execution or GPU correctness. */
final class TerrainHookContract {
    static final String LEVEL = 'net/minecraft/client/renderer/LevelRenderer'
    static final String DISPATCHER = 'net/minecraft/client/renderer/chunk/SectionRenderDispatcher'
    static final String MESH = 'Lnet/minecraft/client/renderer/chunk/SectionMesh;'
    static final String EXTRACT = 'extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I'
    static final String LAYERS = 'net/minecraft/client/renderer/chunk/ChunkSectionLayer.values()[Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;'
    static final String GET_MESH = DISPATCHER + '$RenderSection.getSectionMesh()' + MESH
    static final String GET_SLICE = DISPATCHER + '.getRenderSectionSlice(' + MESH +
            'Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)L' + DISPATCHER + '$RenderSectionBufferSlice;'

    static Map<String, List<String>> calls(byte[] bytes) {
        Map<String, List<String>> calls = [:]
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                List<String> found = []
                calls[name + descriptor] = found
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                        found.add(owner + '.' + method + desc)
                    }
                }
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        return calls
    }
    static List<String> verify(byte[] bytes) { return verifyCalls(calls(bytes)) }
    static List<String> verifyCalls(Map<String, List<String>> methods) {
        List<String> errors = []
        List<String> extractor = methods[EXTRACT] ?: []
        if (extractor.count(LAYERS) != 1 || extractor.indexOf(LAYERS) >= extractor.indexOf(GET_SLICE))
            errors.add('Terrain hook: extractor must enumerate actual ChunkSectionLayer values before layer-specific slices (including CUTOUT)')
        if (extractor.count(GET_MESH) != 1 || extractor.count(GET_SLICE) != 1
                || extractor.indexOf(GET_MESH) >= extractor.indexOf(GET_SLICE))
            errors.add('Terrain hook: expected one owner.getSectionMesh then one dispatcher.getRenderSectionSlice in extractor')
        ['prepareChunkRenders', 'prepareChunkRendersIndirect'].each { name ->
            def entries = methods.findAll { key, value -> key.startsWith(name + '(') }
            if (entries.size() != 1 || entries.values().first().count(LEVEL + '.' + EXTRACT) != 1)
                errors.add("Terrain hook: ${name} must call extractor exactly once".toString())
        }
        def renders = methods.findAll { key, value -> key.startsWith('render(') }
        if (renders.size() != 1) {
            errors.add('Terrain hook: expected one LevelRenderer.render overload')
        } else {
            List<String> render = renders.values().first()
            int compile = render.findIndexOf { it.startsWith(LEVEL + '.compileSections(') }
            int upload = render.findIndexOf { it == DISPATCHER + '.uploadTerrainBuffersToGpu()V' }
            ['prepareChunkRenders', 'prepareChunkRendersIndirect'].each { name ->
                int prepare = render.findIndexOf { it.startsWith(LEVEL + '.' + name + '(') }
                if (prepare < 0 || compile <= prepare || upload <= compile)
                    errors.add("Terrain hook: expected ${name} before compileSections before uploadTerrainBuffersToGpu".toString())
            }
        }
        return errors
    }
}
