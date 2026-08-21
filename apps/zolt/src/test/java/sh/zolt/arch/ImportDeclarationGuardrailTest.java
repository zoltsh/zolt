package sh.zolt.arch;

import static sh.zolt.arch.ArchitectureDiagnostics.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.arch.ModuleTypeReferences.TypeReferenceSite;
import sh.zolt.arch.WorkspaceDependencyDeclarations.ImportSite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * Cross-lib type-usage declaration guardrail (, type-resolved by ).
 *
 * scripts/bootstrap-zolt-jvm compiles the whole workspace in one javac pass, so a
 * cross-lib type resolves even when the importing module never declares the owning
 * module as a workspace dependency -- it rides in transitively through a declared
 * sibling. That hides undeclared module edges and lets a lib build that would break
 * the moment its declared deps are trimmed. This guard asserts that every sh.zolt.*
 * type a module uses from another module is backed by an explicit
 * { workspace = "modules/<name>" } entry in that module's zolt.toml.
 *
 * The guarantee is type-level, not text-level. Cross-module edges are read from the
 * compiled bytecode (ModuleTypeReferences) as the ground-truth source: javac has already
 * resolved every reference, so a type used by fully-qualified name with no `import`, via a
 * wildcard `import sh.zolt.x.*;`, or via a `import static sh.zolt.x.Foo.BAR;` is caught
 * the same as a plain `import sh.zolt.x.Foo;`. The legacy `import`-line scan
 * (WorkspaceDependencyDeclarations.zoltImports) is unioned in so the guard still holds on a
 * source-only checkout whose bytecode has not been built yet; whenever the workspace is
 * compiled (as it always is in the bootstrap arch run), the bytecode closes the holes the
 * line scan alone would miss.
 *
 * Owner resolution is at the fully-qualified-type level (see
 * WorkspaceDependencyDeclarations) so split-named packages never produce a false
 * positive: a module using its own type from a package another module also publishes
 * into is correctly treated as a self-reference.
 */
final class ImportDeclarationGuardrailTest {
    private static final List<Path> MAIN_SOURCES = RepositoryPaths.mainSourceRoots();
    private static final ToolProvider JAVAC = ToolProvider.findFirst("javac").orElseThrow();

    @Test
    void everyCrossLibTypeUsageIsBackedByADeclaredWorkspaceDependency() throws IOException {
        // The bootstrap run compiles every module before the arch suite, so the bytecode
        // ground truth is present. Guard the invariant so a stale/partial build degrades
        // loudly to the line scan rather than silently weakening the type-level guarantee.
        Set<String> compiled = ModuleTypeReferences.compiledModules(MAIN_SOURCES);
        Set<String> allModules = new TreeSet<>();
        for (Path sourceRoot : MAIN_SOURCES) {
            allModules.add(WorkspaceDependencyDeclarations.moduleName(sourceRoot));
        }
        assertEquals(
                allModules,
                compiled,
                "Every module must be compiled (target/classes present) for the type-level cross-lib "
                        + "guard to read ground truth; run scripts/bootstrap-zolt-jvm build --workspace first.");

        List<String> violations = undeclaredCrossModuleEdges(referencesFor(MAIN_SOURCES), MAIN_SOURCES);

        assertTrue(
                violations.isEmpty(),
                () -> "Cross-lib type usage without a declared workspace dependency:\n"
                        + describe(violations)
                        + "\nEach module must declare every other module it uses a sh.zolt.* type from "
                        + "in its zolt.toml [dependencies] or [api.dependencies] block.");
    }

    @Test
    void scannerFlagsAnUndeclaredCrossModuleEdge(@TempDir Path tempDir) throws IOException {
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/ArtifactCacheException.java"),
                "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n");
        write(
                quality.resolve("sh/zolt/quality/LockfileQualityCheck.java"),
                "package sh.zolt.quality;\nimport sh.zolt.cache.ArtifactCacheException;\nfinal class LockfileQualityCheck {}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-quality"), Set.of());
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());

        List<String> violations = undeclaredCrossModuleEdges(
                importReferences(List.of(quality, repository)), List.of(quality, repository));

        assertEquals(1, violations.size());
        String message = violations.get(0);
        assertTrue(message.contains("zolt-quality"), message);
        assertTrue(message.contains("sh.zolt.cache.ArtifactCacheException"), message);
        assertTrue(message.contains("zolt-repository"), message);
        assertTrue(
                message.contains("\"sh.zolt:zolt-repository\" = { workspace = \"modules/zolt-repository\" }"),
                message);
    }

    @Test
    void scannerAcceptsADeclaredCrossModuleEdge(@TempDir Path tempDir) throws IOException {
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/ArtifactCacheException.java"),
                "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n");
        write(
                quality.resolve("sh/zolt/quality/LockfileQualityCheck.java"),
                "package sh.zolt.quality;\nimport sh.zolt.cache.ArtifactCacheException;\nfinal class LockfileQualityCheck {}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-quality"), Set.of("zolt-repository"));
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());

        assertTrue(undeclaredCrossModuleEdges(
                        importReferences(List.of(quality, repository)), List.of(quality, repository))
                .isEmpty());
    }

    @Test
    void scannerAcceptsAnApiDeclaredCrossModuleEdge(@TempDir Path tempDir) throws IOException {
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/ArtifactCacheException.java"),
                "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n");
        write(
                quality.resolve("sh/zolt/quality/LockfileQualityCheck.java"),
                "package sh.zolt.quality;\nimport sh.zolt.cache.ArtifactCacheException;\nfinal class LockfileQualityCheck {}\n");
        write(quality.getParent().getParent().getParent().resolve("zolt.toml"), """
                [project]
                name = "zolt-quality"

                [dependencies.api]
                "sh.zolt:zolt-repository" = { workspace = true }
                """);
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());

        assertTrue(undeclaredCrossModuleEdges(
                        importReferences(List.of(quality, repository)), List.of(quality, repository))
                .isEmpty());
    }

    @Test
    void scannerTreatsAnOwnedSplitPackageImportAsASelfImport(@TempDir Path tempDir) throws IOException {
        // sh.zolt.maven is split: zolt-model declares Coordinate, zolt-repository declares
        // MavenRepositoryClient. zolt-model importing its own Coordinate must NOT be flagged
        // even though zolt-repository also publishes into sh.zolt.maven and is undeclared.
        Path model = tempDir.resolve("modules/zolt-model/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                model.resolve("sh/zolt/maven/Coordinate.java"),
                "package sh.zolt.maven;\npublic final class Coordinate {}\n");
        write(
                model.resolve("sh/zolt/model/Pom.java"),
                "package sh.zolt.model;\nimport sh.zolt.maven.Coordinate;\nfinal class Pom {}\n");
        write(
                repository.resolve("sh/zolt/maven/MavenRepositoryClient.java"),
                "package sh.zolt.maven;\npublic final class MavenRepositoryClient {}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-model"), Set.of());
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());

        assertTrue(undeclaredCrossModuleEdges(importReferences(List.of(model, repository)), List.of(model, repository))
                .isEmpty());
    }

    @Test
    void bytecodeGuardFlagsAFullyQualifiedReferenceWithNoImport(@TempDir Path tempDir) throws IOException {
        // The headline hole: a cross-lib type named only by its fully-qualified name, with no
        // `import` line, is invisible to the legacy import scan but present in the bytecode.
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/ArtifactCacheException.java"),
                "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n");
        write(
                quality.resolve("sh/zolt/quality/LockfileQualityCheck.java"),
                "package sh.zolt.quality;\n"
                        + "final class LockfileQualityCheck {\n"
                        + "  sh.zolt.cache.ArtifactCacheException boom() { return null; }\n"
                        + "}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-quality"), Set.of());
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());
        compileWorkspace(List.of(quality, repository));

        // The import-line scan sees nothing (no `import`), proving the hole; bytecode catches it.
        assertTrue(
                undeclaredCrossModuleEdges(importReferences(List.of(quality, repository)), List.of(quality, repository))
                        .isEmpty(),
                "Sanity: the legacy import-line scan misses the FQN-without-import edge.");

        List<String> violations = undeclaredCrossModuleEdges(
                bytecodeReferences(List.of(quality, repository)), List.of(quality, repository));

        assertEquals(1, violations.size(), () -> "Expected one bytecode-detected edge but got " + violations);
        String message = violations.get(0);
        assertTrue(message.contains("zolt-quality"), message);
        assertTrue(message.contains("sh.zolt.cache.ArtifactCacheException"), message);
        assertTrue(message.contains("zolt-repository"), message);
    }

    @Test
    void bytecodeGuardFlagsAWildcardImportReference(@TempDir Path tempDir) throws IOException {
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/ArtifactCacheException.java"),
                "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n");
        write(
                quality.resolve("sh/zolt/quality/WildcardQualityCheck.java"),
                "package sh.zolt.quality;\n"
                        + "import sh.zolt.cache.*;\n"
                        + "final class WildcardQualityCheck {\n"
                        + "  ArtifactCacheException boom() { return null; }\n"
                        + "}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-quality"), Set.of());
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());
        compileWorkspace(List.of(quality, repository));

        // A wildcard import has no resolvable type on the import line, so the legacy scan misses it.
        assertTrue(
                undeclaredCrossModuleEdges(importReferences(List.of(quality, repository)), List.of(quality, repository))
                        .isEmpty(),
                "Sanity: the legacy import-line scan cannot resolve a wildcard-import edge.");

        List<String> violations = undeclaredCrossModuleEdges(
                bytecodeReferences(List.of(quality, repository)), List.of(quality, repository));

        assertEquals(1, violations.size(), () -> "Expected one bytecode-detected edge but got " + violations);
        assertTrue(violations.get(0).contains("sh.zolt.cache.ArtifactCacheException"), violations.get(0));
        assertTrue(violations.get(0).contains("zolt-repository"), violations.get(0));
    }

    @Test
    void bytecodeGuardFlagsAStaticImportReference(@TempDir Path tempDir) throws IOException {
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/CacheConstants.java"),
                "package sh.zolt.cache;\npublic final class CacheConstants { public static final String NAME = \"cache\"; }\n");
        write(
                quality.resolve("sh/zolt/quality/StaticImportQualityCheck.java"),
                "package sh.zolt.quality;\n"
                        + "import static sh.zolt.cache.CacheConstants.NAME;\n"
                        + "final class StaticImportQualityCheck {\n"
                        + "  String name() { return NAME; }\n"
                        + "}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-quality"), Set.of());
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());
        compileWorkspace(List.of(quality, repository));

        List<String> violations = undeclaredCrossModuleEdges(
                bytecodeReferences(List.of(quality, repository)), List.of(quality, repository));

        assertEquals(1, violations.size(), () -> "Expected one bytecode-detected edge but got " + violations);
        assertTrue(violations.get(0).contains("sh.zolt.cache.CacheConstants"), violations.get(0));
        assertTrue(violations.get(0).contains("zolt-repository"), violations.get(0));
    }

    @Test
    void bytecodeGuardAcceptsADeclaredCrossModuleReference(@TempDir Path tempDir) throws IOException {
        Path quality = tempDir.resolve("modules/zolt-quality/src/main/java");
        Path repository = tempDir.resolve("modules/zolt-repository/src/main/java");
        write(
                repository.resolve("sh/zolt/cache/ArtifactCacheException.java"),
                "package sh.zolt.cache;\npublic final class ArtifactCacheException extends RuntimeException {}\n");
        write(
                quality.resolve("sh/zolt/quality/LockfileQualityCheck.java"),
                "package sh.zolt.quality;\n"
                        + "final class LockfileQualityCheck {\n"
                        + "  sh.zolt.cache.ArtifactCacheException boom() { return null; }\n"
                        + "}\n");
        writeModuleConfig(tempDir.resolve("modules/zolt-quality"), Set.of("zolt-repository"));
        writeModuleConfig(tempDir.resolve("modules/zolt-repository"), Set.of());
        compileWorkspace(List.of(quality, repository));

        assertTrue(undeclaredCrossModuleEdges(
                        bytecodeReferences(List.of(quality, repository)), List.of(quality, repository))
                .isEmpty());
    }

    private static List<Reference> referencesFor(List<Path> sourceRoots) throws IOException {
        List<Reference> references = new ArrayList<>(importReferences(sourceRoots));
        references.addAll(bytecodeReferences(sourceRoots));
        return references;
    }

    private static List<Reference> importReferences(List<Path> sourceRoots) throws IOException {
        List<Reference> references = new ArrayList<>();
        for (ImportSite site : WorkspaceDependencyDeclarations.zoltImports(sourceRoots)) {
            references.add(new Reference(site.module(), site.importedReference(), site.file()));
        }
        return references;
    }

    private static List<Reference> bytecodeReferences(List<Path> sourceRoots) throws IOException {
        List<Reference> references = new ArrayList<>();
        for (TypeReferenceSite site : ModuleTypeReferences.typeReferences(sourceRoots)) {
            references.add(new Reference(site.module(), site.referencedType(), site.classFile()));
        }
        return references;
    }

    private static List<String> undeclaredCrossModuleEdges(List<Reference> references, List<Path> sourceRoots)
            throws IOException {
        Map<String, String> typeOwners = WorkspaceDependencyDeclarations.typeOwners(sourceRoots);
        Map<String, Set<String>> declarationsByModule = declarationsByModule(sourceRoots);
        Map<String, String> exampleFiles = new TreeMap<>();
        Set<String> reported = new TreeSet<>();

        for (Reference reference : references) {
            Optional<String> owner =
                    WorkspaceDependencyDeclarations.resolveOwner(reference.referencedType(), typeOwners);
            if (owner.isEmpty()) {
                continue;
            }
            String owningModule = owner.orElseThrow();
            if (owningModule.equals(reference.module())) {
                continue; // self-reference: the module owns the type itself.
            }
            Set<String> declared = declarationsByModule.getOrDefault(reference.module(), Set.of());
            if (declared.contains(owningModule)) {
                continue;
            }
            String key = reference.module() + "|" + reference.referencedType() + "|" + owningModule;
            reported.add(key);
            exampleFiles.putIfAbsent(key, RepositoryPaths.displayPath(reference.file()));
        }

        List<String> violations = new ArrayList<>();
        for (String key : reported) {
            String[] parts = key.split("\\|", -1);
            String importingModule = parts[0];
            String referencedType = parts[1];
            String owningModule = parts[2];
            violations.add(importingModule
                    + " uses " + referencedType
                    + " owned by " + owningModule
                    + " (e.g. " + exampleFiles.get(key) + ")"
                    + " but does not declare it; add \"sh.zolt:" + owningModule
                    + "\" = { workspace = \"modules/" + owningModule + "\" } to "
                    + moduleConfigPath(importingModule, sourceRoots));
        }
        return violations;
    }

    private static Map<String, Set<String>> declarationsByModule(List<Path> sourceRoots) throws IOException {
        Map<String, Set<String>> declarations = new TreeMap<>();
        for (Path sourceRoot : sourceRoots) {
            String module = WorkspaceDependencyDeclarations.moduleName(sourceRoot);
            Path moduleRoot = WorkspaceDependencyDeclarations.moduleRoot(sourceRoot);
            declarations.put(module, WorkspaceDependencyDeclarations.declaredWorkspaceDependencies(moduleRoot));
        }
        return declarations;
    }

    private static String moduleConfigPath(String module, List<Path> sourceRoots) {
        for (Path sourceRoot : sourceRoots) {
            if (WorkspaceDependencyDeclarations.moduleName(sourceRoot).equals(module)) {
                Path config = WorkspaceDependencyDeclarations.moduleRoot(sourceRoot).resolve("zolt.toml");
                return RepositoryPaths.displayPath(config);
            }
        }
        return module + "/zolt.toml";
    }

    /**
     * Compiles each fixture module into its own {@code <module>/target/classes} output, so the bytecode
     * reader reads the same per-module layout the real workspace build produces. Cross-module references
     * are resolved from the sibling source roots via {@code -sourcepath}, with {@code -implicit:none} so
     * those siblings are used only for type resolution and never emit their {@code .class} into this
     * module's output -- keeping each emitted class attributed to exactly one module.
     */
    private static void compileWorkspace(List<Path> sourceRoots) throws IOException {
        for (Path sourceRoot : sourceRoots) {
            Files.createDirectories(ModuleTypeReferences.classesRoot(sourceRoot));
        }
        for (Path sourceRoot : sourceRoots) {
            compileModule(sourceRoot, sourceRoots);
        }
    }

    private static void compileModule(Path sourceRoot, List<Path> allSourceRoots) throws IOException {
        List<String> javaFiles = new ArrayList<>();
        for (Path file : ArchitectureSourceFiles.javaFiles(List.of(sourceRoot))) {
            javaFiles.add(file.toString());
        }
        if (javaFiles.isEmpty()) {
            return;
        }
        StringBuilder sourcePath = new StringBuilder();
        for (Path root : allSourceRoots) {
            if (sourcePath.length() > 0) {
                sourcePath.append(java.io.File.pathSeparatorChar);
            }
            sourcePath.append(root);
        }
        Path classes = ModuleTypeReferences.classesRoot(sourceRoot);
        List<String> args = new ArrayList<>(List.of(
                "-d",
                classes.toString(),
                "-sourcepath",
                sourcePath.toString(),
                "-implicit:none",
                "--release",
                "21"));
        args.addAll(javaFiles);
        int status = JAVAC.run(System.out, System.err, args.toArray(new String[0]));
        if (status != 0) {
            throw new IllegalStateException("Fixture module compilation failed for " + sourceRoot + ": " + status);
        }
    }

    private static void writeModuleConfig(Path moduleRoot, Set<String> workspaceDependencies) throws IOException {
        StringBuilder config = new StringBuilder("[project]\nname = \"")
                .append(moduleRoot.getFileName())
                .append("\"\n\n[dependencies]\n");
        for (String dependency : workspaceDependencies) {
            config.append("\"sh.zolt:")
                    .append(dependency)
                    .append("\" = { workspace = \"modules/")
                    .append(dependency)
                    .append("\" }\n");
        }
        write(moduleRoot.resolve("zolt.toml"), config.toString());
    }

    private static void write(Path path, String contents) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents);
    }

    /** A cross-module type reference site, sourced from either an import line or compiled bytecode. */
    private record Reference(String module, String referencedType, Path file) {
    }
}
