package sh.zolt.build.abi;

import sh.zolt.build.BuildException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ClassFileAbiReader {
    private static final int JAVA_CLASS_MAGIC = 0xCAFEBABE;
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final Set<String> COMPILE_RELEVANT_ATTRIBUTES = Set.of(
            "AnnotationDefault",
            "ConstantValue",
            "Deprecated",
            "Exceptions",
            "InnerClasses",
            "NestHost",
            "NestMembers",
            "PermittedSubclasses",
            "Record",
            "RuntimeInvisibleAnnotations",
            "RuntimeInvisibleTypeAnnotations",
            "RuntimeVisibleAnnotations",
            "RuntimeVisibleTypeAnnotations",
            "Signature");

    public ClassFileAbi read(Path classFile) {
        Path normalized = classFile.toAbsolutePath().normalize();
        try {
            byte[] bytes = Files.readAllBytes(normalized);
            ParsedClass parsed = parse(normalized, bytes);
            return new ClassFileAbi(
                    parsed.binaryName(),
                    normalized,
                    parsed.sourceFileName(),
                    parsed.accessFlags(),
                    parsed.superName(),
                    parsed.interfaces(),
                    sha256(lines(parsed.publicAbiLines())),
                    sha256(lines(parsed.packagePrivateAbiLines())),
                    parsed.referencedClasses());
        } catch (IOException exception) {
            throw new BuildException(
                    "Could not read class file ABI from "
                            + normalized
                            + ". Check that the class file is readable.",
                    exception);
        }
    }

    private static ParsedClass parse(Path classFile, byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();
            if (magic != JAVA_CLASS_MAGIC) {
                throw new BuildException("Class file " + classFile + " has an invalid Java class header.");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            ClassFileConstantPool constantPool = ClassFileConstantPool.read(input);
            int accessFlags = input.readUnsignedShort();
            String binaryName = constantPool.className(input.readUnsignedShort());
            int superIndex = input.readUnsignedShort();
            Optional<String> superName = superIndex == 0
                    ? Optional.empty()
                    : Optional.of(constantPool.className(superIndex));
            List<String> interfaces = readInterfaces(input, constantPool);
            List<MemberAbi> fields = readMembers(input, constantPool, "field");
            List<MemberAbi> methods = readMembers(input, constantPool, "method");
            ClassAttributes classAttributes = readClassAttributes(input, constantPool);

            Set<String> referencedClasses = new LinkedHashSet<>(constantPool.referencedClasses());
            fields.forEach(field -> field.addReferences(referencedClasses));
            methods.forEach(method -> method.addReferences(referencedClasses));
            referencedClasses.remove(binaryName);

            List<String> classLines = new ArrayList<>();
            classLines.add("class|" + accessFlags + "|" + binaryName + "|"
                    + superName.orElse("") + "|" + String.join(",", interfaces));
            classAttributes.compileRelevant().forEach(attribute -> classLines.add("classAttribute|" + attribute));

            List<String> publicAbi = new ArrayList<>();
            List<String> packagePrivateAbi = new ArrayList<>();
            if (isPublicOrProtected(accessFlags)) {
                publicAbi.addAll(classLines);
            }
            if (!isPrivate(accessFlags)) {
                packagePrivateAbi.addAll(classLines);
            }
            fields.stream()
                    .filter(member -> !member.isPrivate())
                    .sorted(Comparator.comparing(MemberAbi::line))
                    .forEach(member -> {
                        if (member.isPublicOrProtected()) {
                            publicAbi.add(member.line());
                        }
                        packagePrivateAbi.add(member.line());
                    });
            methods.stream()
                    .filter(member -> !member.isPrivate())
                    .filter(member -> !member.name().equals("<clinit>"))
                    .sorted(Comparator.comparing(MemberAbi::line))
                    .forEach(member -> {
                        if (member.isPublicOrProtected()) {
                            publicAbi.add(member.line());
                        }
                        packagePrivateAbi.add(member.line());
                    });

            return new ParsedClass(
                    binaryName,
                    classAttributes.sourceFileName(),
                    accessFlags,
                    superName,
                    interfaces,
                    publicAbi.stream().sorted().toList(),
                    packagePrivateAbi.stream().sorted().toList(),
                    referencedClasses.stream().sorted().toList());
        }
    }

    private static List<String> readInterfaces(DataInputStream input, ClassFileConstantPool constantPool)
            throws IOException {
        int count = input.readUnsignedShort();
        List<String> interfaces = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            interfaces.add(constantPool.className(input.readUnsignedShort()));
        }
        return interfaces.stream().sorted().toList();
    }

    private static List<MemberAbi> readMembers(
            DataInputStream input,
            ClassFileConstantPool constantPool,
            String kind) throws IOException {
        int count = input.readUnsignedShort();
        List<MemberAbi> members = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int accessFlags = input.readUnsignedShort();
            String name = constantPool.utf8(input.readUnsignedShort());
            String descriptor = constantPool.utf8(input.readUnsignedShort());
            List<String> attributes = readCompileRelevantAttributes(input, constantPool);
            members.add(new MemberAbi(kind, accessFlags, name, descriptor, attributes));
        }
        return members;
    }

    private static ClassAttributes readClassAttributes(
            DataInputStream input,
            ClassFileConstantPool constantPool) throws IOException {
        int count = input.readUnsignedShort();
        List<String> attributes = new ArrayList<>();
        Optional<String> sourceFileName = Optional.empty();
        for (int index = 0; index < count; index++) {
            String name = constantPool.utf8(input.readUnsignedShort());
            byte[] bytes = input.readNBytes(input.readInt());
            if ("SourceFile".equals(name) && bytes.length == 2) {
                try (DataInputStream attributeInput = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    sourceFileName = Optional.of(constantPool.utf8(attributeInput.readUnsignedShort()));
                }
            } else if (COMPILE_RELEVANT_ATTRIBUTES.contains(name)) {
                attributes.add(name + "=" + sha256(bytes));
            }
        }
        return new ClassAttributes(
                sourceFileName,
                attributes.stream().sorted().toList());
    }

    private static List<String> readCompileRelevantAttributes(
            DataInputStream input,
            ClassFileConstantPool constantPool) throws IOException {
        int count = input.readUnsignedShort();
        List<String> attributes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String name = constantPool.utf8(input.readUnsignedShort());
            byte[] bytes = input.readNBytes(input.readInt());
            if ("ConstantValue".equals(name) && bytes.length == 2) {
                try (DataInputStream attributeInput = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    attributes.add(name + "=" + constantPool.constantValue(attributeInput.readUnsignedShort()));
                }
            } else if (COMPILE_RELEVANT_ATTRIBUTES.contains(name)) {
                attributes.add(name + "=" + sha256(bytes));
            }
        }
        return attributes.stream().sorted().toList();
    }

    private static boolean isPrivate(int accessFlags) {
        return (accessFlags & ACC_PRIVATE) != 0;
    }

    private static boolean isPublicOrProtected(int accessFlags) {
        return (accessFlags & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
    }

    private static byte[] lines(List<String> lines) {
        return String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new BuildException("Could not compute class file ABI because SHA-256 is unavailable.", exception);
        }
    }

    private record ParsedClass(
            String binaryName,
            Optional<String> sourceFileName,
            int accessFlags,
            Optional<String> superName,
            List<String> interfaces,
            List<String> publicAbiLines,
            List<String> packagePrivateAbiLines,
            List<String> referencedClasses) {
    }

    private record ClassAttributes(
            Optional<String> sourceFileName,
            List<String> compileRelevant) {
    }

    private record MemberAbi(
            String kind,
            int accessFlags,
            String name,
            String descriptor,
            List<String> attributes) {
        String line() {
            return kind + "|" + accessFlags + "|" + name + "|" + descriptor + "|" + String.join(",", attributes);
        }

        boolean isPrivate() {
            return ClassFileAbiReader.isPrivate(accessFlags);
        }

        boolean isPublicOrProtected() {
            return ClassFileAbiReader.isPublicOrProtected(accessFlags);
        }

        void addReferences(Set<String> referencedClasses) {
            ClassFileConstantPool.addDescriptorReferences(descriptor, referencedClasses);
        }
    }
}
