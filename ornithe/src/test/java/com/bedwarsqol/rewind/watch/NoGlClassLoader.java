package com.bedwarsqol.rewind.watch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A child-first class loader in which OpenGL does nothing, so the real HUD painters can run in a unit test (review
 * code-2 I2, D5): Minecraft, LWJGL and Cobblify classes are loaded again from the test class path, and every method of
 * every class in {@code org.lwjgl.opengl} (GL11, GL14, ARB and EXT entry points, {@code GLContext}, {@code Display})
 * returns at once with a zero, false or null, its static initializer dropped (no native library is loaded). Plain
 * Java, JDK, JUnit, ASM and the libraries that hold no game state come from the parent. Everything loaded here is
 * separate from the test's own copies (their static state included), and is dropped with the loader.
 */
final class NoGlClassLoader extends ClassLoader {

    private static final String[] PARENT_FIRST = {"java.", "javax.", "jdk.", "sun.", "com.sun.", "org.junit.",
            "junit.", "org.hamcrest.", "org.objectweb.asm.", "org.apache.", "com.google.", "io.netty.",
            "com.mojang.authlib.", "org.slf4j.", "it.unimi."};

    /** Every stubbed OpenGL class name, in load order (the test lists them on a failure). */
    final List<String> stubbed = new ArrayList<String>();

    NoGlClassLoader(ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                c = parentFirst(name) ? getParent().loadClass(name) : defineHere(name);
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    private static boolean parentFirst(String name) {
        for (String p : PARENT_FIRST) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private Class<?> defineHere(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = getParent().getResourceAsStream(path)) {
            if (in == null) {
                return getParent().loadClass(name);
            }
            bytes = read(in);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
        if (name.startsWith("org.lwjgl.opengl.")) {
            bytes = stub(bytes);
            stubbed.add(name);
        }
        return defineClass(name, bytes, 0, bytes.length);
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Every method but constructors returns its type's zero; no static initializer; no native method. */
    static byte[] stub(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (Iterator<MethodNode> it = cn.methods.iterator(); it.hasNext(); ) {
            MethodNode m = it.next();
            if (m.name.equals("<clinit>")) {
                it.remove();
                continue;
            }
            if (m.name.equals("<init>") || (m.access & Opcodes.ACC_ABSTRACT) != 0) {
                continue;
            }
            m.access &= ~Opcodes.ACC_NATIVE;
            m.instructions = zeroReturn(Type.getReturnType(m.desc));
            m.tryCatchBlocks.clear();
            if (m.localVariables != null) {
                m.localVariables.clear();
            }
            m.visibleLocalVariableAnnotations = null;
            m.invisibleLocalVariableAnnotations = null;
        }
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(w);
        return w.toByteArray();
    }

    private static InsnList zeroReturn(Type t) {
        InsnList l = new InsnList();
        switch (t.getSort()) {
            case Type.VOID:
                l.add(new InsnNode(Opcodes.RETURN));
                break;
            case Type.LONG:
                l.add(new LdcInsnNode(0L));
                l.add(new InsnNode(Opcodes.LRETURN));
                break;
            case Type.FLOAT:
                l.add(new InsnNode(Opcodes.FCONST_0));
                l.add(new InsnNode(Opcodes.FRETURN));
                break;
            case Type.DOUBLE:
                l.add(new InsnNode(Opcodes.DCONST_0));
                l.add(new InsnNode(Opcodes.DRETURN));
                break;
            case Type.OBJECT:
            case Type.ARRAY:
                l.add(new InsnNode(Opcodes.ACONST_NULL));
                l.add(new InsnNode(Opcodes.ARETURN));
                break;
            default: // boolean, byte, char, short, int
                l.add(new InsnNode(Opcodes.ICONST_0));
                l.add(new InsnNode(Opcodes.IRETURN));
                break;
        }
        return l;
    }
}
