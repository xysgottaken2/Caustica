package dev.comfyfluffy.caustica.build

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.*

class AbiContractTest {
    @TempDir Path temp
    private static final String OWNER = 'example/Device'
    private static final String CONTRACT = 'example/Device\tM\tcreateDevice\t()J\nexample/Device\tF\tbackend\tJ\n'

    private static byte[] fixture(String descriptor = '()J') {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, OWNER, null, 'java/lang/Object', null)
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, 'createDevice', descriptor, null, null).visitEnd()
        writer.visitField(Opcodes.ACC_PRIVATE, 'backend', 'J', null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private File jar(String name, byte[] bytes) {
        File target = temp.resolve(name).toFile()
        new ZipOutputStream(new FileOutputStream(target)).withCloseable { zip ->
            zip.putNextEntry(new ZipEntry("${OWNER}.class"))
            zip.write(bytes)
            zip.closeEntry()
        }
        return target
    }

    @Test void matchesExactDescriptorsInJava25Jar() {
        assertTrue(AbiContract.verify(AbiContract.parse(CONTRACT), [jar('client.jar', fixture())]).empty)
    }

    @Test void detectsChangedOverload() {
        def failures = AbiContract.verify(AbiContract.parse(CONTRACT), [jar('client.jar', fixture('(I)J'))])
        assertEquals(1, failures.size())
        assertTrue(failures[0].contains('Missing member:'))
    }

    @Test void detectsMissingClass() {
        assertEquals(['Missing class: example/Device'], AbiContract.verify(AbiContract.parse(CONTRACT), []))
    }

    @Test void acceptsIdenticalDuplicatesButRejectsConflicts() {
        File first = jar('first.jar', fixture())
        assertTrue(AbiContract.verify(AbiContract.parse(CONTRACT), [first, jar('same.jar', fixture())]).empty)
        assertThrows(IllegalArgumentException) {
            AbiContract.verify(AbiContract.parse(CONTRACT), [first, jar('different.jar', fixture('(I)J'))])
        }
    }

    @Test void readsClassDirectory() {
        File target = temp.resolve("${OWNER}.class").toFile()
        target.parentFile.mkdirs()
        target.bytes = fixture()
        assertTrue(AbiContract.verify(AbiContract.parse(CONTRACT), [temp.toFile()]).empty)
    }

    @Test void rejectsWrongClassName() {
        assertThrows(IllegalArgumentException) { AbiContract.members(fixture(), 'wrong/Device') }
    }

    @Test void rejectsEmptyMalformedAndDuplicateContracts() {
        ['', '# Comment only', 'a\tM\tb', '../a\tM\tb\t()V', 'a\tX\tb\t()V', CONTRACT + CONTRACT].each { text ->
            assertThrows(IllegalArgumentException) { AbiContract.parse(text) }
        }
    }
}
