import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class PatchRemoveLast {
  public static void main(String[] args) throws Exception {
    Path in = Path.of(args[0]);
    Path out = Path.of(args[1]);
    Files.createDirectories(out.getParent());
    int patched = 0;
    try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(in)));
         ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
      ZipEntry e;
      byte[] buf = new byte[8192];
      while ((e = zis.getNextEntry()) != null) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int n;
        while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
        byte[] data = bos.toByteArray();
        if (e.getName().endsWith(".class") && (contains(data, "removeLast") || contains(data, "removeFirst"))) {
          byte[] rewritten = patchClass(data);
          if (rewritten != data) {
            data = rewritten;
            patched++;
            System.out.println("Patched " + e.getName());
          }
        }
        zos.putNextEntry(new ZipEntry(e.getName()));
        zos.write(data);
        zos.closeEntry();
      }
    }
    System.out.println("Patched classes: " + patched);
  }

  static boolean contains(byte[] data, String s) {
    byte[] needle = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    outer:
    for (int i = 0; i <= data.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (data[i + j] != needle[j]) continue outer;
      }
      return true;
    }
    return false;
  }

  static byte[] patchClass(byte[] input) {
    ClassReader cr = new ClassReader(input);
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
    final boolean[] changed = {false};
    cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
      @Override
      public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM9, mv) {
          @Override
          public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            boolean listFamily =
                "java/util/ArrayList".equals(owner)
                    || "java/util/List".equals(owner)
                    || "java/util/AbstractList".equals(owner);
            if (listFamily && "()Ljava/lang/Object;".equals(descriptor)) {
              boolean useInterface = isInterface || "java/util/List".equals(owner);
              int invoke = useInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
              if ("removeLast".equals(name)) {
                super.visitInsn(Opcodes.DUP);
                super.visitMethodInsn(invoke, owner, "size", "()I", useInterface);
                super.visitInsn(Opcodes.ICONST_1);
                super.visitInsn(Opcodes.ISUB);
                super.visitMethodInsn(invoke, owner, "remove", "(I)Ljava/lang/Object;", useInterface);
                changed[0] = true;
                return;
              }
              if ("removeFirst".equals(name)) {
                super.visitInsn(Opcodes.ICONST_0);
                super.visitMethodInsn(invoke, owner, "remove", "(I)Ljava/lang/Object;", useInterface);
                changed[0] = true;
                return;
              }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
          }
        };
      }
    }, ClassReader.EXPAND_FRAMES);
    return changed[0] ? cw.toByteArray() : input;
  }
}
