package sh.zolt.build.abi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClassFileConstantPoolTest {
    @Test
    void normalizesClassNamesAndReferencedClassesDeterministically() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeShort(14);
            output.writeByte(1);
            output.writeUTF("com/example/Beta");
            output.writeByte(7);
            output.writeShort(1);
            output.writeByte(1);
            output.writeUTF("Ljava/util/List;Lcom/example/Alpha;");
            output.writeByte(1);
            output.writeUTF("[Lcom/example/Array;");
            output.writeByte(7);
            output.writeShort(4);
            output.writeByte(1);
            output.writeUTF("I");
            output.writeByte(7);
            output.writeShort(6);
            output.writeByte(1);
            output.writeUTF("[I");
            output.writeByte(7);
            output.writeShort(8);
            output.writeByte(1);
            output.writeUTF("[LA;");
            output.writeByte(7);
            output.writeShort(10);
            output.writeByte(1);
            output.writeUTF("p/Outer$Inner");
            output.writeByte(7);
            output.writeShort(12);
        }

        ClassFileConstantPool constantPool = ClassFileConstantPool.read(input(bytes));

        assertEquals("com.example.Beta", constantPool.className(2));
        assertEquals("com.example.Array", constantPool.className(5));
        assertEquals("I", constantPool.className(7));
        assertEquals("", constantPool.className(9));
        assertEquals("A", constantPool.className(11));
        assertEquals("p.Outer$Inner", constantPool.className(13));
        assertEquals(
                List.of(
                        "A",
                        "I",
                        "com.example.Alpha",
                        "com.example.Array",
                        "com.example.Beta",
                        "java.util.List",
                        "p.Outer$Inner"),
                constantPool.referencedClasses());
    }

    @Test
    void reportsUnsupportedConstantPoolTags() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeShort(2);
            output.writeByte(99);
        }

        BuildException exception = assertThrows(BuildException.class, () -> ClassFileConstantPool.read(input(bytes)));

        assertTrue(exception.getMessage().contains("Unsupported class file constant pool tag `99`."));
    }

    @Test
    void reportsInvalidUtf8References() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeShort(3);
            output.writeByte(7);
            output.writeShort(2);
            output.writeByte(3);
            output.writeInt(1);
        }
        ClassFileConstantPool constantPool = ClassFileConstantPool.read(input(bytes));

        BuildException exception = assertThrows(BuildException.class, () -> constantPool.className(1));

        assertTrue(exception.getMessage().contains("Invalid class file constant pool UTF-8 reference `2`."));
    }

    private static DataInputStream input(ByteArrayOutputStream bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }
}
