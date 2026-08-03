package sh.zolt.build.abi;

import sh.zolt.build.BuildException;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ClassFileConstantPool {
    private final Object[] entries;

    private ClassFileConstantPool(Object[] entries) {
        this.entries = entries;
    }

    static ClassFileConstantPool read(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        Object[] entries = new Object[count];
        for (int index = 1; index < count; index++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1 -> entries[index] = input.readUTF();
                case 3 -> entries[index] = new IntegerInfo(input.readInt());
                case 4 -> entries[index] = new FloatInfo(input.readInt());
                case 5 -> {
                    entries[index] = new LongInfo(input.readLong());
                    index++;
                }
                case 6 -> {
                    entries[index] = new DoubleInfo(input.readLong());
                    index++;
                }
                case 7 -> entries[index] = new ClassInfo(input.readUnsignedShort());
                case 8 -> entries[index] = new StringInfo(input.readUnsignedShort());
                case 16, 19, 20 -> input.skipBytes(2);
                case 9, 10, 11, 12, 17, 18 -> input.skipBytes(4);
                case 15 -> input.skipBytes(3);
                default -> throw new BuildException("Unsupported class file constant pool tag `" + tag + "`.");
            }
        }
        return new ClassFileConstantPool(entries);
    }

    String utf8(int index) {
        Object entry = entries[index];
        if (entry instanceof String value) {
            return value;
        }
        throw new BuildException("Invalid class file constant pool UTF-8 reference `" + index + "`.");
    }

    String className(int index) {
        Object entry = entries[index];
        if (entry instanceof ClassInfo classInfo) {
            return normalizeClassName(utf8(classInfo.nameIndex()));
        }
        throw new BuildException("Invalid class file constant pool class reference `" + index + "`.");
    }

    String constantValue(int index) {
        Object entry = entries[index];
        if (entry instanceof IntegerInfo integerInfo) {
            return "int:" + integerInfo.value();
        }
        if (entry instanceof FloatInfo floatInfo) {
            return "float-bits:" + Integer.toUnsignedString(floatInfo.bits(), 16);
        }
        if (entry instanceof LongInfo longInfo) {
            return "long:" + longInfo.value();
        }
        if (entry instanceof DoubleInfo doubleInfo) {
            return "double-bits:" + Long.toUnsignedString(doubleInfo.bits(), 16);
        }
        if (entry instanceof StringInfo stringInfo) {
            return "string:" + utf8(stringInfo.utf8Index());
        }
        throw new BuildException("Invalid class file constant value reference `" + index + "`.");
    }

    List<String> referencedClasses() {
        Set<String> classes = new LinkedHashSet<>();
        for (Object entry : entries) {
            if (entry instanceof ClassInfo classInfo) {
                classes.add(normalizeClassName(utf8(classInfo.nameIndex())));
            } else if (entry instanceof String value) {
                addDescriptorReferences(value, classes);
            }
        }
        classes.remove("");
        return classes.stream().sorted().toList();
    }

    static void addDescriptorReferences(String value, Set<String> referencedClasses) {
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf('L', index);
            if (start < 0) {
                return;
            }
            int end = value.indexOf(';', start);
            if (end < 0) {
                return;
            }
            String candidate = value.substring(start + 1, end);
            if (!candidate.isBlank() && candidate.indexOf(' ') < 0) {
                referencedClasses.add(normalizeClassName(candidate));
            }
            index = end + 1;
        }
    }

    private static String normalizeClassName(String internalName) {
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.length() == 1) {
            return "";
        }
        return name.replace('/', '.');
    }

    private record ClassInfo(int nameIndex) {
    }

    private record IntegerInfo(int value) {
    }

    private record FloatInfo(int bits) {
    }

    private record LongInfo(long value) {
    }

    private record DoubleInfo(long bits) {
    }

    private record StringInfo(int utf8Index) {
    }
}
