package dev.comfyfluffy.caustica.build

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.util.zip.ZipFile

/** Reads bytecode without loading Minecraft classes or initializing LWJGL/native libraries. */
final class AbiContract {
    static List<List<String>> parse(String text) {
        List<List<String>> entries = []
        text.readLines().eachWithIndex { String line, int index ->
            if (!line.isBlank() && !line.stripLeading().startsWith('#')) {
                List<String> parts = line.split('\t', -1).toList()
                if (parts.size() != 4 || parts.any { it.isBlank() } || !(parts[1] in ['M', 'F']) ||
                        parts[0].contains('..') || parts[0].startsWith('/') || parts[0].contains('\\')) {
                    throw new IllegalArgumentException("Invalid ABI contract line ${index + 1}")
                }
                entries.add(parts)
            }
        }
        if (entries.empty) throw new IllegalArgumentException('Empty ABI contract')
        if (entries.toSet().size() != entries.size()) throw new IllegalArgumentException('Duplicate ABI contract entry')
        return entries
    }

    static Set<String> members(byte[] bytes, String expectedOwner) {
        ClassReader reader = new ClassReader(bytes)
        if (reader.className != expectedOwner) {
            throw new IllegalArgumentException("Expected ${expectedOwner}, found ${reader.className}")
        }
        Set<String> found = new HashSet<>()
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                found.add("M\t${name}\t${descriptor}".toString())
                return null
            }

            @Override
            FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                found.add("F\t${name}\t${descriptor}".toString())
                return null
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
        return found
    }

    static List<String> verify(List<List<String>> entries, Collection<File> classpath) {
        Map<String, List<List<String>>> owners = entries.groupBy { it[0] }
        Map<String, byte[]> classes = [:]
        classpath.each { File file ->
            if (file.isDirectory()) {
                owners.keySet().each { String owner ->
                    File candidate = new File(file, "${owner}.class")
                    if (candidate.isFile()) addClass(classes, owner, candidate.bytes)
                }
            } else if (file.isFile() && file.name.endsWith('.jar')) {
                new ZipFile(file).withCloseable { ZipFile zip ->
                    owners.keySet().each { String owner ->
                        def entry = zip.getEntry("${owner}.class")
                        if (entry != null) {
                            byte[] bytes = zip.getInputStream(entry).withCloseable { it.readAllBytes() }
                            addClass(classes, owner, bytes)
                        }
                    }
                }
            }
        }
        List<String> errors = []
        owners.each { String owner, List<List<String>> requirements ->
            if (!classes.containsKey(owner)) {
                errors.add("Missing class: ${owner}".toString())
            } else {
                if (owner == TerrainHookContract.LEVEL && requirements.any { it[2] == 'extractSectionDrawGroups' })
                    errors.addAll(TerrainHookContract.verify(classes[owner]))
                errors.addAll(EntityHookContract.verify(owner,classes[owner]))
                Set<String> available = members(classes[owner], owner)
                requirements.each { List<String> requirement ->
                    String member = requirement.subList(1, 4).join('\t')
                    if (!available.contains(member)) errors.add("Missing member: ${requirement.join('\t')}".toString())
                }
            }
        }
        return errors
    }

    private static void addClass(Map<String, byte[]> classes, String owner, byte[] bytes) {
        byte[] previous = classes.putIfAbsent(owner, bytes)
        if (previous != null && !Arrays.equals(previous, bytes)) {
            throw new IllegalArgumentException("Conflicting classpath definitions: ${owner}")
        }
    }
}
