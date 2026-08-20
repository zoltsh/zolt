package sh.zolt.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the acyclic, Tomlj-contained package boundary for the final manifest parser. */
final class TomlManifestPackageBoundaryTest {
    private static final String ROOT_PACKAGE = "sh.zolt.toml";
    private static final String MANIFEST_PACKAGE = ROOT_PACKAGE + ".manifest";
    private static final String SCHEMA_PACKAGE = ROOT_PACKAGE + ".schema";
    private static final String SYNTAX_PACKAGE = ROOT_PACKAGE + ".syntax";
    private static final String TOMLJ_PACKAGE = "org.tomlj";

    @Test
    void finalManifestPackagesKeepTheirDependencyDirectionAndTomljBoundary() throws IOException {
        Path root = RepositoryPaths.root();
        Path tomlSources = root.resolve("modules/zolt-toml/src/main/java/sh/zolt/toml");
        Map<String, String> typeOwners =
                WorkspaceDependencyDeclarations.typeOwners(RepositoryPaths.mainSourceRoots());

        List<Violation> violations = violations(tomlSources, typeOwners);

        assertTrue(
                violations.isEmpty(),
                () -> "Final-manifest TOML package boundary violations:\n  "
                        + String.join("\n  ", violations.stream().map(Violation::description).toList())
                        + "\nKeep syntax JDK-only, schema independent of manifest, legacy TOML independent "
                        + "of the final pipeline, and Tomlj inside package-private manifest internals.");
    }

    @Test
    void scannerRejectsEveryForbiddenEdgeButAllowsModelAndInternalTomlj(
            @TempDir Path tempDir) throws IOException {
        Path tomlSources = tempDir.resolve("sh/zolt/toml");
        write(tomlSources.resolve("syntax/BadSyntax.java"), """
                package sh.zolt.toml.syntax;
                import org.tomlj.Toml;
                public final class BadSyntax {}
                """);
        write(tomlSources.resolve("schema/BadSchema.java"), """
                package sh.zolt.toml.schema;
                import sh.zolt.toml.manifest.ManifestSyntax;
                final class BadSchema {}
                """);
        write(tomlSources.resolve("schema/BadTomljSchema.java"), """
                package sh.zolt.toml.schema;
                import org.tomlj.TomlTable;
                final class BadTomljSchema {}
                """);
        write(tomlSources.resolve("schema/SchemaDocumentation.java"), """
                package sh.zolt.toml.schema;
                /*
                import sh.zolt.toml.manifest.ManifestSyntax;
                import org.tomlj.TomlTable;
                */
                final class SchemaDocumentation {
                    String note = "sh.zolt.toml.manifest stays downstream";
                }
                """);
        write(tomlSources.resolve("BadRoot.java"), """
                package sh.zolt.toml;
                import sh.zolt.toml.syntax.SourceSpan;
                final class BadRoot {}
                """);
        write(tomlSources.resolve("manifest/PublicLeak.java"), """
                package sh.zolt.toml.manifest;
                import org.tomlj.TomlTable;
                public final class PublicLeak { public TomlTable value() { return null; } }
                """);
        write(tomlSources.resolve("manifest/BadDependency.java"), """
                package sh.zolt.toml.manifest;
                import sh.zolt.cli.Command;
                final class BadDependency {}
                """);
        write(tomlSources.resolve("manifest/GoodDecoder.java"), """
                package sh.zolt.toml.manifest;
                import org.tomlj.TomlTable;
                import sh.zolt.manifest.authored.AuthoredManifest;
                final class GoodDecoder { TomlTable input; AuthoredManifest output; }
                """);
        write(tomlSources.resolve("manifest/GoodNested.java"), """
                package sh.zolt.toml.manifest;
                final class GoodNested { LocalIndex.Entry value; }
                """);
        write(tomlSources.resolve("manifest/PublicDocumentation.java"), """
                package sh.zolt.toml.manifest;
                /** Public API does not expose org.tomlj.TomlTable. */
                public final class PublicDocumentation {
                    public String diagnostic() { return "org.tomlj.TomlTable stays internal"; }
                }
                """);
        Map<String, String> typeOwners = Map.of(
                "sh.zolt.cli.Command", "zolt",
                "sh.zolt.toml.manifest.LocalIndex", "zolt-toml",
                "sh.zolt.manifest.authored.AuthoredManifest", "zolt-model");

        List<Violation> violations = violations(tomlSources, typeOwners);

        assertEquals(6, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(value -> value.rule().equals("syntax-jdk-only")));
        assertTrue(violations.stream().anyMatch(value -> value.rule().equals("schema-no-manifest")));
        assertTrue(violations.stream().anyMatch(value -> value.rule().equals("schema-no-tomlj")));
        assertTrue(violations.stream().anyMatch(value -> value.rule().equals("legacy-no-final-pipeline")));
        assertTrue(violations.stream().anyMatch(value -> value.rule().equals("manifest-dependencies")));
        assertTrue(violations.stream().anyMatch(value -> value.rule().equals("public-api-no-tomlj")));
    }

    @Test
    void scannerRejectsDirectReferencesAndMismatchedPackages(@TempDir Path tempDir)
            throws IOException {
        Path sources = tempDir.resolve("sh/zolt/toml");
        write(sources.resolve("syntax/DirectSyntax.java"), """
                package sh.zolt.toml.syntax;
                final class DirectSyntax { org.tomlj.Toml value; }
                """);
        write(sources.resolve("schema/DirectManifest.java"), """
                package sh.zolt.toml.schema;
                final class DirectManifest { sh.zolt.toml.manifest.ManifestSyntax value; }
                """);
        write(sources.resolve("schema/DirectTomlj.java"), """
                package sh.zolt.toml.schema;
                final class DirectTomlj { org.tomlj.TomlTable value; }
                """);
        write(sources.resolve("dependency/BadLegacy.java"), """
                package sh.zolt.toml.dependency;
                final class BadLegacy { sh.zolt.toml.schema.ManifestField value; }
                """);
        write(sources.resolve("manifest/BadDirectDependencies.java"), """
                package sh.zolt.toml.manifest;
                final class BadDirectDependencies {
                    sh.zolt.cli.Command command;
                    com.google.Foo thirdParty;
                    com.example.lowercase lowerThree;
                    vendor.lowercase lowerTwo;
                }
                """);
        write(sources.resolve("manifest/PublicFqn.java"), """
                package sh.zolt.toml.manifest;
                public @Deprecated final class $Facade { org.tomlj.TomlTable value; }
                """);
        write(sources.resolve("manifest/WrongPackage.java"), """
                package sh.zolt.toml.schema;
                final class WrongPackage {}
                """);

        List<Violation> violations = violations(sources, Map.of("sh.zolt.cli.Command", "zolt"));

        assertEquals(10, violations.size(), violations.toString());
        assertEquals(1, count(violations, "package-path-mismatch"));
        assertEquals(1, count(violations, "syntax-jdk-only"));
        assertEquals(1, count(violations, "schema-no-manifest"));
        assertEquals(1, count(violations, "schema-no-tomlj"));
        assertEquals(1, count(violations, "legacy-no-final-pipeline"));
        assertEquals(4, count(violations, "manifest-dependencies"));
        assertEquals(1, count(violations, "public-api-no-tomlj"));
    }

    @Test
    void scannerPreservesStaticAndWildcardImportMeaning(@TempDir Path tempDir)
            throws IOException {
        Path sources = tempDir.resolve("sh/zolt/toml");
        write(sources.resolve("syntax/AllowedJdk.java"), """
                package sh.zolt.toml.syntax;
                import java.util.*;
                import static java.util.Objects.*;
                final class AllowedJdk {}
                """);
        write(sources.resolve("syntax/BadStatic.java"), """
                package sh.zolt.toml.syntax;
                import static
                    org.tomlj.Toml.parse;
                final class BadStatic {}
                """);
        write(sources.resolve("schema/BadWildcards.java"), """
                package sh.zolt.toml.schema;
                import org.tomlj.*;
                import
                    sh.zolt.toml.manifest.*;
                final class BadWildcards {}
                """);
        write(sources.resolve("dependency/BadLegacyWildcard.java"), """
                package sh.zolt.toml.dependency;
                import sh.zolt.toml.schema.*;
                final class BadLegacyWildcard {}
                """);
        write(sources.resolve("manifest/GoodWildcards.java"), """
                package sh.zolt.toml.manifest;
                import javax.lang.model.*;
                import org.tomlj.*;
                import sh.zolt.manifest.*;
                import static sh.zolt.manifest.ManifestModelValues.*;
                final class GoodWildcards {}
                """);
        write(sources.resolve("manifest/BadWildcard.java"), """
                package sh.zolt.toml.manifest;
                import sh.zolt.cli.*;
                final class BadWildcard {}
                """);
        write(sources.resolve("manifest/BadDescendantOnlyWildcard.java"), """
                package sh.zolt.toml.manifest;
                import sh.zolt.onlydesc.*;
                final class BadDescendantOnlyWildcard {}
                """);
        write(sources.resolve("manifest/BadMixedModelWildcard.java"), """
                package sh.zolt.toml.manifest;
                import sh.zolt.mixed.*;
                final class BadMixedModelWildcard {}
                """);
        Map<String, String> owners = Map.of(
                "sh.zolt.cli.Command", "zolt",
                "sh.zolt.manifest.ManifestModelValues", "zolt-model",
                "sh.zolt.manifest.ProjectName", "zolt-model",
                "sh.zolt.manifest.internal.NonModelType", "zolt",
                "sh.zolt.onlydesc.nested.ModelType", "zolt-model",
                "sh.zolt.mixed.ModelType", "zolt-model",
                "sh.zolt.mixed.NonModelType", "zolt");

        List<Violation> violations = violations(sources, owners);

        assertEquals(7, violations.size(), violations.toString());
        assertEquals(1, count(violations, "syntax-jdk-only"));
        assertEquals(1, count(violations, "schema-no-manifest"));
        assertEquals(1, count(violations, "schema-no-tomlj"));
        assertEquals(1, count(violations, "legacy-no-final-pipeline"));
        assertEquals(3, count(violations, "manifest-dependencies"));
        assertTrue(violations.stream().noneMatch(value ->
                value.file().endsWith("GoodWildcards.java")));
        assertTrue(violations.stream().anyMatch(value ->
                value.file().endsWith("BadDescendantOnlyWildcard.java")));
        assertTrue(violations.stream().anyMatch(value ->
                value.file().endsWith("BadMixedModelWildcard.java")));
    }

    private static List<Violation> violations(Path tomlSources, Map<String, String> typeOwners)
            throws IOException {
        List<Violation> violations = new ArrayList<>();
        validatePackagePaths(tomlSources, violations);
        scanSyntax(tomlSources.resolve("syntax"), violations);
        scanSchema(tomlSources.resolve("schema"), violations);
        scanLegacy(tomlSources, violations);
        scanManifest(tomlSources.resolve("manifest"), typeOwners, violations);
        return List.copyOf(violations);
    }

    private static void validatePackagePaths(Path tomlSources, List<Violation> violations)
            throws IOException {
        for (Path file : javaFiles(tomlSources)) {
            String actual = JavaSourceReferences.packageName(Files.readString(file)).orElse("<missing>");
            String expected = expectedPackage(tomlSources, file);
            if (!actual.equals(expected)) {
                violations.add(new Violation(
                        "package-path-mismatch",
                        file,
                        1,
                        "declares package `" + actual + "` but its source path requires `" + expected + "`"));
            }
        }
    }

    private static void scanSyntax(Path directory, List<Violation> violations) throws IOException {
        for (Path file : javaFiles(directory)) {
            for (JavaSourceReferences.Reference reference : references(file)) {
                if (!isJdk(reference.name()) && !isWithin(reference.name(), SYNTAX_PACKAGE)) {
                    violations.add(new Violation(
                            "syntax-jdk-only", file, reference.line(),
                            "references non-JDK type or package `" + reference.name() + "`"));
                }
            }
        }
    }

    private static void scanSchema(Path directory, List<Violation> violations) throws IOException {
        for (Path file : javaFiles(directory)) {
            for (JavaSourceReferences.Reference reference : references(file)) {
                if (isWithin(reference.name(), MANIFEST_PACKAGE)) {
                    violations.add(new Violation(
                            "schema-no-manifest", file, reference.line(),
                            "references downstream manifest type or package `" + reference.name() + "`"));
                }
                if (isWithin(reference.name(), TOMLJ_PACKAGE)) {
                    violations.add(new Violation(
                            "schema-no-tomlj", file, reference.line(),
                            "references Tomlj type or package `" + reference.name() + "`"));
                }
            }
        }
    }

    private static void scanLegacy(Path directory, List<Violation> violations) throws IOException {
        for (Path file : javaFiles(directory)) {
            Path relative = directory.relativize(file);
            if (isFinalPackage(relative)) {
                continue;
            }
            for (JavaSourceReferences.Reference reference : references(file)) {
                if (isWithin(reference.name(), MANIFEST_PACKAGE)
                        || isWithin(reference.name(), SCHEMA_PACKAGE)
                        || isWithin(reference.name(), SYNTAX_PACKAGE)) {
                    violations.add(new Violation(
                            "legacy-no-final-pipeline", file, reference.line(),
                            "legacy TOML source references final-pipeline type or package `"
                                    + reference.name() + "`"));
                }
            }
        }
    }

    private static void scanManifest(
            Path directory,
            Map<String, String> typeOwners,
            List<Violation> violations) throws IOException {
        for (Path file : javaFiles(directory)) {
            String source = Files.readString(file);
            JavaSourceReferences.Analysis analysis = JavaSourceReferences.analyze(source);
            List<JavaSourceReferences.Reference> references = analysis.references();
            for (JavaSourceReferences.Reference reference : references) {
                if (!allowedManifestReference(reference, typeOwners)) {
                    violations.add(new Violation(
                            "manifest-dependencies", file, reference.line(),
                            "references type or package outside JDK/syntax/schema/model/root exception/Tomlj: `"
                                    + reference.name() + "`"));
                }
            }
            if (analysis.publicTopLevelType()) {
                references.stream()
                        .filter(reference -> isWithin(reference.name(), TOMLJ_PACKAGE))
                        .min(java.util.Comparator.comparingInt(JavaSourceReferences.Reference::line))
                        .ifPresent(reference -> violations.add(new Violation(
                                "public-api-no-tomlj",
                                file,
                                reference.line(),
                                "declares a public top-level type in a source file that references Tomlj")));
            }
        }
    }

    private static boolean allowedManifestReference(
            JavaSourceReferences.Reference reference,
            Map<String, String> typeOwners) {
        String name = reference.name();
        if (isJdk(name)
                || isWithin(name, TOMLJ_PACKAGE)
                || isWithin(name, MANIFEST_PACKAGE)
                || isWithin(name, SCHEMA_PACKAGE)
                || isWithin(name, SYNTAX_PACKAGE)
                || isWithin(name, ROOT_PACKAGE + ".ZoltConfigException")) {
            return true;
        }
        if (WorkspaceDependencyDeclarations.resolveOwner(MANIFEST_PACKAGE + "." + name, typeOwners)
                .filter("zolt-toml"::equals)
                .isPresent()) {
            return true;
        }
        if (!isWithin(name, "sh.zolt")) {
            return false;
        }
        if (WorkspaceDependencyDeclarations.resolveOwner(name, typeOwners)
                .filter("zolt-model"::equals)
                .isPresent()) {
            return true;
        }
        if (!reference.wildcard()) {
            return false;
        }
        List<String> wildcardOwners = typeOwners.entrySet().stream()
                .filter(entry -> immediatePackage(entry.getKey()).equals(name))
                .map(Map.Entry::getValue)
                .distinct()
                .toList();
        return !wildcardOwners.isEmpty() && wildcardOwners.stream().allMatch("zolt-model"::equals);
    }

    private static String immediatePackage(String typeName) {
        int separator = typeName.lastIndexOf('.');
        return separator < 0 ? "" : typeName.substring(0, separator);
    }

    private static List<JavaSourceReferences.Reference> references(Path file) throws IOException {
        return JavaSourceReferences.references(Files.readString(file));
    }

    private static List<Path> javaFiles(Path directory) throws IOException {
        return ArchitectureSourceFiles.javaFiles(List.of(directory));
    }

    private static boolean isFinalPackage(Path relative) {
        if (relative.getNameCount() < 2) {
            return false;
        }
        String first = relative.getName(0).toString();
        return first.equals("manifest") || first.equals("schema") || first.equals("syntax");
    }

    private static String expectedPackage(Path tomlSources, Path file) {
        StringBuilder expected = new StringBuilder(ROOT_PACKAGE);
        Path relativeParent = tomlSources.relativize(file.getParent());
        for (Path segment : relativeParent) {
            if (!segment.toString().isEmpty()) {
                expected.append('.').append(segment);
            }
        }
        return expected.toString();
    }

    private static boolean isJdk(String reference) {
        return isWithin(reference, "java") || isWithin(reference, "javax");
    }

    private static boolean isWithin(String reference, String packageOrType) {
        return reference.equals(packageOrType) || reference.startsWith(packageOrType + ".");
    }

    private static long count(List<Violation> violations, String rule) {
        return violations.stream().filter(value -> value.rule().equals(rule)).count();
    }

    private static void write(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private record Violation(String rule, Path file, int line, String detail) {
        String description() {
            return RepositoryPaths.displayPath(file) + ":" + line + " [" + rule + "] " + detail;
        }
    }
}
