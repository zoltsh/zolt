package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class JavaSourceReferencesTest {
    @Test
    void findsLowercaseFqnsWithoutTreatingOnlyInScopeNamesAsPackages() {
        String source = """
                package sh.zolt.toml.manifest;
                import java.util.Objects;
                final class UnrelatedNames {
                    Object com;
                    static final class sh {}
                }
                final class Decoder {
                    com.example.lowercase lowerThree;
                    vendor.lowercase lowerTwo;
                    Vendor.lowercase uppercasePackageRoot;
                    sh.zolt.cli.Command command;
                    void decode(Context context) {
                        context.diagnostics.add();
                        Objects.requireNonNull(context);
                        Helper.call();
                    }
                    void declarationsAfterUseDoNotHidePackages() {
                        com.example.later beforeCom;
                        Object com = null;
                        sh.zolt.cli.Later beforeSh;
                        Object sh = null;
                    }
                    void packageTypeWinsOverPriorValueName() {
                        Object com = null;
                        com.example.lowercase afterCom;
                    }
                    static final class Context { Diagnostics diagnostics; }
                    static final class Diagnostics { void add() {} }
                    static final class Helper { static void call() {} }
                }
                """;

        assertEquals(
                List.of(
                        "java.util.Objects",
                        "com.example.lowercase",
                        "vendor.lowercase",
                        "Vendor.lowercase",
                        "sh.zolt.cli.Command",
                        "com.example.later",
                        "sh.zolt.cli.Later",
                        "com.example.lowercase"),
                names(source));
    }

    @Test
    void ignoresCommentsStringsCharactersAndEscapedTextBlockTriples() {
        String triple = "\"\"\"";
        String source = "package sh.zolt.toml.manifest;\n"
                + "final class Masking {\n"
                + "// com.google.LineComment\n"
                + "/* net.example.BlockComment */\n"
                + "String normal = \"prefix \\\" io.example.StringValue\";\n"
                + "char quote = '\\\"';\n"
                + "String block = " + triple + "\n"
                + "com.google.TextBlock\n"
                + "\\" + triple + "\n"
                + "net.example.AfterEscapedTriple\n"
                + triple + ";\n"
                + "com.google.RealType value;\n"
                + "String evenBlock = " + triple + "\n"
                + "io.example.EvenTextBlock\n"
                + "\\\\" + triple + ";\n"
                + "net.example.EvenParity even;\n"
                + "}\n";

        assertEquals(
                List.of("com.google.RealType", "net.example.EvenParity"),
                names(source));
    }

    @Test
    void identifiesEveryTopLevelPublicTypeShape() {
        assertTrue(publicTopLevel("public @Deprecated final class $Facade {}"));
        assertTrue(publicTopLevel("public final class ΩFacade {}"));
        assertTrue(publicTopLevel("public record ZoltManifestDocument(String source) {}"));
        assertTrue(publicTopLevel("public sealed interface ManifestNode {}"));
        assertFalse(publicTopLevel("final class AuthoredProjectDecoder {}"));
        assertFalse(publicTopLevel("final class Decoder { public static class Internal {} }"));
    }

    private static List<String> names(String source) {
        return JavaSourceReferences.references(source).stream()
                .map(JavaSourceReferences.Reference::name)
                .toList();
    }

    private static boolean publicTopLevel(String source) {
        return JavaSourceReferences.declaresPublicTopLevelType(source);
    }
}
