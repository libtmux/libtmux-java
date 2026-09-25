package io.github.libtmux;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Kotlin sees a Java collection as mutable unless the method carries {@code @ReadOnly}, so a
 * returned list would offer {@code add} and throw. The annotation is kept only in the class file,
 * which is where this reads it.
 */
final class ReadOnlyCollectionsTest {

    private static final String READ_ONLY = "Lkotlin/annotations/jvm/ReadOnly;";
    private static final Set<String> COLLECTIONS = Set.of(
            "java/util/Collection",
            "java/util/List",
            "java/util/Set",
            "java/util/Map",
            "java/util/SequencedCollection",
            "java/util/SequencedSet",
            "java/util/SequencedMap",
            "java/util/SortedSet",
            "java/util/SortedMap",
            "java/util/NavigableSet",
            "java/util/NavigableMap");

    @Test
    void everyCollectionThePublicApiReturnsIsReadOnlyToKotlin() throws IOException, URISyntaxException {
        Path classes = Path.of(
                Server.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> mutable = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.toString().contains("/internal/"))
                    .sorted()
                    .toList()) {
                new ClassReader(Files.readAllBytes(file)).accept(new Returns(mutable), ClassReader.SKIP_CODE);
            }
        }

        assertEquals(List.of(), mutable);
    }

    /** Records each public method of a public type that returns a collection without the mark. */
    private static final class Returns extends ClassVisitor {

        private final List<String> mutable;
        private String type = "";
        private boolean published;

        Returns(List<String> mutable) {
            super(Opcodes.ASM9);
            this.mutable = mutable;
        }

        @Override
        public void visit(
                int version, int access, String name, String signature, String superName, String[] interfaces) {
            type = name;
            published = (access & Opcodes.ACC_PUBLIC) != 0;
        }

        @Override
        public @Nullable MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            boolean api = published
                    && (access & Opcodes.ACC_PUBLIC) != 0
                    && (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0;
            if (!api || !COLLECTIONS.contains(Type.getReturnType(descriptor).getInternalName())) {
                return null;
            }
            return new MethodVisitor(Opcodes.ASM9) {
                private boolean readOnly;

                @Override
                public @Nullable AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    readOnly |= annotation.equals(READ_ONLY);
                    return null;
                }

                @Override
                public void visitEnd() {
                    if (!readOnly) {
                        mutable.add(type.replace('/', '.') + "." + name);
                    }
                }
            };
        }
    }
}
