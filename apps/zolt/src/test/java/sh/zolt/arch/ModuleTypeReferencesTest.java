package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Self-tests for the bytecode constant-pool reader that backs the type-level cross-lib
 * guarantee. These compile tiny fixtures with the in-process javac, then assert
 * that referencedZoltTypes() recovers the cross-lib type edges that the legacy import-line
 * scan missed: a fully-qualified reference with no import, a wildcard import, and a static
 * import all resolve to ordinary constant-pool entries, so the bytecode reader sees them.
 */
final class ModuleTypeReferencesTest {
    private static final ToolProvider JAVAC = ToolProvider.findFirst("javac").orElseThrow();

    @Test
    void recoversTypeReferencedByFullyQualifiedNameWithNoImport(@TempDir Path tempDir) throws IOException {
        Path classes = compile(
                tempDir,
                source(
                        "sh/zolt/cache/ArtifactCacheException.java",
                        "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n"),
                source(
                        "sh/zolt/quality/LockfileQualityCheck.java",
                        // No import: the cross-lib type is named only by its fully-qualified name.
                        "package sh.zolt.quality;\n"
                                + "final class LockfileQualityCheck {\n"
                                + "  sh.zolt.cache.ArtifactCacheException boom() { return null; }\n"
                                + "}\n"));

        Set<String> referenced =
                ModuleTypeReferences.referencedZoltTypes(classes.resolve("sh/zolt/quality/LockfileQualityCheck.class"));

        assertTrue(
                referenced.contains("sh.zolt.cache.ArtifactCacheException"),
                () -> "FQN-without-import reference must be recovered from bytecode: " + referenced);
    }

    @Test
    void recoversTypeReachedThroughAWildcardImport(@TempDir Path tempDir) throws IOException {
        Path classes = compile(
                tempDir,
                source(
                        "sh/zolt/cache/ArtifactCacheException.java",
                        "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n"),
                source(
                        "sh/zolt/quality/WildcardQualityCheck.java",
                        "package sh.zolt.quality;\n"
                                + "import sh.zolt.cache.*;\n"
                                + "final class WildcardQualityCheck {\n"
                                + "  ArtifactCacheException boom() { return null; }\n"
                                + "}\n"));

        Set<String> referenced =
                ModuleTypeReferences.referencedZoltTypes(classes.resolve("sh/zolt/quality/WildcardQualityCheck.class"));

        assertTrue(
                referenced.contains("sh.zolt.cache.ArtifactCacheException"),
                () -> "Wildcard-import reference must be recovered from bytecode: " + referenced);
    }

    @Test
    void recoversTypeReachedThroughAStaticImport(@TempDir Path tempDir) throws IOException {
        Path classes = compile(
                tempDir,
                source(
                        "sh/zolt/cache/CacheConstants.java",
                        "package sh.zolt.cache;\npublic final class CacheConstants { public static final String NAME = \"cache\"; }\n"),
                source(
                        "sh/zolt/quality/StaticImportQualityCheck.java",
                        "package sh.zolt.quality;\n"
                                + "import static sh.zolt.cache.CacheConstants.NAME;\n"
                                + "final class StaticImportQualityCheck {\n"
                                + "  String name() { return NAME; }\n"
                                + "}\n"));

        Set<String> referenced =
                ModuleTypeReferences.referencedZoltTypes(
                        classes.resolve("sh/zolt/quality/StaticImportQualityCheck.class"));

        assertTrue(
                referenced.contains("sh.zolt.cache.CacheConstants"),
                () -> "Static-import reference must be recovered from bytecode: " + referenced);
    }

    @Test
    void ignoresNonZoltTypesAndBarePackageFragments(@TempDir Path tempDir) throws IOException {
        Path classes = compile(
                tempDir,
                source(
                        "sh/zolt/quality/PlainCheck.java",
                        "package sh.zolt.quality;\n"
                                + "import java.util.List;\n"
                                + "final class PlainCheck { List<String> values() { return java.util.List.of(); } }\n"));

        Set<String> referenced =
                ModuleTypeReferences.referencedZoltTypes(classes.resolve("sh/zolt/quality/PlainCheck.class"));

        assertTrue(
                referenced.stream().allMatch(type -> type.startsWith("sh.zolt.")),
                () -> "Only sh.zolt.* types must be reported: " + referenced);
        // The class declares its own package but references no other sh.zolt type.
        assertFalse(
                referenced.contains("sh.zolt.quality"),
                () -> "A bare package fragment is not a type and must be ignored: " + referenced);
    }

    private static Path compile(Path tempDir, Source... sources) throws IOException {
        Path sourceRoot = tempDir.resolve("src");
        Path classes = tempDir.resolve("classes");
        Files.createDirectories(classes);
        String[] args = new String[sources.length + 5];
        args[0] = "-d";
        args[1] = classes.toString();
        args[2] = "-proc:none";
        args[3] = "--release";
        args[4] = "21";
        for (int i = 0; i < sources.length; i++) {
            Path file = sourceRoot.resolve(sources[i].relativePath());
            Files.createDirectories(file.getParent());
            Files.writeString(file, sources[i].contents());
            args[i + 5] = file.toString();
        }
        int status = JAVAC.run(System.out, System.err, args);
        if (status != 0) {
            throw new IllegalStateException("Fixture compilation failed with status " + status);
        }
        return classes;
    }

    private static Source source(String relativePath, String contents) {
        return new Source(relativePath, contents);
    }

    private record Source(String relativePath, String contents) {
    }
}
