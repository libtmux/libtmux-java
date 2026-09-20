package libtmux.internal.loadguard;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Rejects resource creation while the native runner requires public namespaces. */
public final class NamespaceAgent {
    private static final String GUARD = "libtmux/internal/loadguard/LoadGuard";
    private static final Map<String, String> TARGETS = Map.ofEntries(
            Map.entry("java/util/concurrent/ThreadPoolExecutor", "executor"),
            Map.entry("java/util/concurrent/ForkJoinPool", "executor"),
            Map.entry("java/util/concurrent/ThreadPerTaskExecutor", "executor"),
            Map.entry("java/lang/Thread", "thread"),
            Map.entry("java/lang/VirtualThread", "thread"),
            Map.entry("java/lang/ProcessBuilder", "process"),
            Map.entry("java/net/Socket", "socket"),
            Map.entry("java/net/ServerSocket", "socket"),
            Map.entry("java/net/DatagramSocket", "socket"),
            Map.entry("sun/nio/ch/SocketChannelImpl", "socket"),
            Map.entry("sun/nio/ch/ServerSocketChannelImpl", "socket"),
            Map.entry("sun/nio/ch/DatagramChannelImpl", "socket"));

    private NamespaceAgent() {}

    public static void premain(String bootstrapJars, Instrumentation instrumentation) throws Exception {
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("namespace load guard requires class retransformation");
        }
        if (bootstrapJars == null || bootstrapJars.isBlank()) {
            throw new IllegalStateException("namespace load guard needs bootstrap helper and ASM JARs");
        }
        var location = NamespaceAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        String[] jars = bootstrapJars.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator));
        if (jars.length != 2 || jars[0].isBlank() || jars[1].isBlank()) {
            throw new IllegalStateException("namespace load guard needs bootstrap helper and ASM JARs");
        }
        for (String jar : jars) {
            instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(jar));
        }
        Class<?> guard = Class.forName(GUARD.replace('/', '.'), true, null);
        instrumentation.redefineModule(
                Object.class.getModule(), Set.of(guard.getModule()), Map.of(), Map.of(), Set.of(), Map.of());
        Map<String, Integer> coverage = new HashMap<>();
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    Module module,
                    ClassLoader loader,
                    String className,
                    Class<?> redefining,
                    ProtectionDomain domain,
                    byte[] bytes) {
                String kind = className == null ? null : TARGETS.get(className);
                if (kind == null) {
                    return null;
                }
                ClassReader reader = new ClassReader(bytes);
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                int[] count = {0};
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor original = super.visitMethod(access, name, descriptor, signature, exceptions);
                        boolean target = switch (kind) {
                            case "thread" -> name.equals("start");
                            case "process" -> name.equals("start") || name.equals("startPipeline");
                            default -> name.equals("<init>");
                        };
                        if (!target || (access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
                            return original;
                        }
                        count[0]++;
                        return new MethodVisitor(Opcodes.ASM9, original) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                visitLdcInsn(kind);
                                visitLdcInsn(className + "." + name + descriptor);
                                visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        GUARD,
                                        "check",
                                        "(Ljava/lang/String;Ljava/lang/String;)V",
                                        false);
                            }
                        };
                    }
                }, 0);
                byte[] transformed = writer.toByteArray();
                coverage.put(className, count[0]);
                return transformed;
            }
        }, true);
        for (String target : TARGETS.keySet()) {
            Class<?> type = Class.forName(target.replace('/', '.'), false, null);
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException("namespace load guard cannot transform " + target);
            }
            coverage.remove(target);
            instrumentation.retransformClasses(type);
            if (coverage.getOrDefault(target, 0) == 0) {
                throw new IllegalStateException("namespace load guard intercepted no methods on " + target);
            }
        }
        guard.getMethod("install", Map.class).invoke(null, coverage);
        System.setProperty(
                "libtmux.clojure.guard.agent", "-javaagent:" + new java.io.File(location) + "=" + bootstrapJars);
        System.setProperty("libtmux.clojure.guard.classpath", new java.io.File(location).toString());
    }
}
