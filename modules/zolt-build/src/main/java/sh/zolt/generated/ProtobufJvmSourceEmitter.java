package sh.zolt.generated;

import java.util.stream.Stream;

/** Renders deterministic Java or Kotlin source shells for the typed Protobuf generator. */
final class ProtobufJvmSourceEmitter {
    private ProtobufJvmSourceEmitter() {
    }

    static String extension(String language) {
        return "kotlin".equals(language) ? ".kt" : ".java";
    }

    static String message(String language, String javaPackage, String message) {
        if ("kotlin".equals(language)) {
            String type = kotlinIdentifier(message);
            return kotlinPackageLine(javaPackage)
                    + "\n"
                    + "class "
                    + type
                    + " {\n"
                    + "    companion object {\n"
                    + "        @JvmStatic\n"
                    + "        fun getDefaultInstance(): "
                    + type
                    + " = "
                    + type
                    + "()\n"
                    + "    }\n"
                    + "}\n";
        }
        return javaPackageLine(javaPackage)
                + "\n"
                + "public final class "
                + message
                + " {\n"
                + "    public "
                + message
                + "() {\n"
                + "    }\n\n"
                + "    public static "
                + message
                + " getDefaultInstance() {\n"
                + "        return new "
                + message
                + "();\n"
                + "    }\n"
                + "}\n";
    }

    static String grpc(String language, String javaPackage, String protoPackage, String service) {
        String serviceName = protoPackage == null || protoPackage.isBlank()
                ? service
                : protoPackage + "." + service;
        if ("kotlin".equals(language)) {
            return kotlinPackageLine(javaPackage)
                    + "\n"
                    + "object "
                    + kotlinIdentifier(service + "Grpc")
                    + " {\n"
                    + "    @JvmStatic\n"
                    + "    fun serviceName(): String = "
                    + JavaSourceLiterals.string(serviceName)
                    + "\n"
                    + "}\n";
        }
        return javaPackageLine(javaPackage)
                + "\n"
                + "public final class "
                + service
                + "Grpc {\n"
                + "    private "
                + service
                + "Grpc() {\n"
                + "    }\n\n"
                + "    public static String serviceName() {\n"
                + "        return "
                + JavaSourceLiterals.string(serviceName)
                + ";\n"
                + "    }\n"
                + "}\n";
    }

    private static String javaPackageLine(String javaPackage) {
        return javaPackage == null || javaPackage.isBlank() ? "" : "package " + javaPackage + ";\n";
    }

    private static String kotlinPackageLine(String javaPackage) {
        if (javaPackage == null || javaPackage.isBlank()) {
            return "";
        }
        String packageName = String.join(
                ".",
                Stream.of(javaPackage.split("\\."))
                        .map(ProtobufJvmSourceEmitter::kotlinIdentifier)
                        .toList());
        return "package " + packageName + "\n";
    }

    private static String kotlinIdentifier(String value) {
        return "`" + value + "`";
    }
}
