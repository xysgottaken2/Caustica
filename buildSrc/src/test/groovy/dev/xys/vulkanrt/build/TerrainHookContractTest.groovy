package dev.xys.vulkanrt.build

import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import static org.junit.jupiter.api.Assertions.*
import static dev.xys.vulkanrt.build.TerrainHookContract.*

class TerrainHookContractTest {
    private static Map<String, List<String>> expected() {
        [(EXTRACT): [GET_MESH, LAYERS, GET_SLICE],
         'prepareChunkRenders()V': [LEVEL + '.' + EXTRACT],
         'prepareChunkRendersIndirect()V': [LEVEL + '.' + EXTRACT],
         'render()V': [LEVEL + '.prepareChunkRenders()V', LEVEL + '.prepareChunkRendersIndirect()V',
                       LEVEL + '.compileSections()V', DISPATCHER + '.uploadTerrainBuffersToGpu()V']]
    }
    @Test void matchesBothTerrainPaths() { assertTrue(verifyCalls(expected()).empty) }
    @Test void rejectsMissingOrAmbiguousRedirects() {
        def missing = expected(); missing[EXTRACT] = [GET_MESH]
        assertFalse(verifyCalls(missing).empty)
        def duplicate = expected(); duplicate[EXTRACT].add(GET_SLICE)
        assertFalse(verifyCalls(duplicate).empty)
    }
    @Test void rejectsChangedRenderLifetimeOrder() {
        def methods = expected(); methods['render()V'] = methods['render()V'].reverse()
        assertFalse(verifyCalls(methods).empty)
    }
    @Test void rejectsExtractorThatNoLongerEnumeratesCutoutLayer() {
        def methods=expected(); methods[EXTRACT].remove(LAYERS)
        assertFalse(verifyCalls(methods).empty)
    }
    @Test void readsCallsFromClassfileInsteadOfSkippingCode() {
        def writer = new ClassWriter(0)
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, LEVEL, null, 'java/lang/Object', null)
        def method = writer.visitMethod(Opcodes.ACC_PUBLIC, 'example', '()V', null, null)
        method.visitCode()
        method.visitMethodInsn(Opcodes.INVOKESTATIC, 'example/Owner', 'target', '()V', false)
        method.visitInsn(Opcodes.RETURN); method.visitMaxs(0,0); method.visitEnd(); writer.visitEnd()
        assertEquals(['example/Owner.target()V'], calls(writer.toByteArray())['example()V'])
    }
}
