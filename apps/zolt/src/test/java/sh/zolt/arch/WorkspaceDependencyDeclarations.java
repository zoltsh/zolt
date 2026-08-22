package sh.zolt.arch;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * Type-level ownership index for the cross-lib import guardrail.
 *
 * The existing SourceFileParser collapses every import to its 3-segment
 * top-level package (sh.zolt.<x>) and drops the class name. That is too coarse
 * for owner resolution: historically several packages were split across modules
 * (sh.zolt.maven lived in zolt-model AND zolt-repository, etc.). Even after
 *  carved those into single-owner subpackages, owner resolution must
 * stay at the fully-qualified-type level so that, for example, importing your
 * own type from a same-named package is never misattributed to a sibling module.
 *
 * The index keys off the file that actually declares the type: package
 * declaration + primary type name (the source file name). sh.zolt.maven.Coordinate
 * resolves to the module whose source file declares it, independent of any
 * other module that may also publish a sh.zolt.maven.* type.
 */
final class WorkspaceDependencyDeclarations {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern IMPORT_PATTERN =
            Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?\\s*;");
    private static final Pattern WORKSPACE_DEP_PATTERN =
            Pattern.compile("\"sh\\.zolt:([\\w-]+)\"\\s*=\\s*\\{[^}]*workspace\\s*=\\s*(?:true|\")");
    private static final Pattern SECTION_HEADER_PATTERN = Pattern.compile("^\\s*\\[([^\\]]+)\\]\\s*$");
    private static final Set<String> COMPILE_DEPENDENCY_SECTIONS =
            Set.of("dependencies", "dependencies.api", "api.dependencies");

    private WorkspaceDependencyDeclarations() {
    }

    /**
     * Maps each fully-qualified sh.zolt.* type (package + primary type name) to the
     * single module whose main sources declare it. Built from the file's package
     * declaration and source file name, so split-named packages resolve per type.
     */
    static Map<String, String> typeOwners(List<Path> sourceRoots) throws IOException {
        Map<String, String> owners = new TreeMap<>();
        for (Path sourceRoot : sourceRoots) {
            String module = moduleName(sourceRoot);
            for (Path javaFile : ArchitectureSourceFiles.javaFiles(List.of(sourceRoot))) {
                Optional<String> packageName = packageDeclaration(javaFile);
                if (packageName.isEmpty()) {
                    continue;
                }
                String primaryType = primaryTypeName(javaFile);
                String fullyQualifiedType = packageName.orElseThrow() + "." + primaryType;
                owners.put(fullyQualifiedType, module);
            }
        }
        return owners;
    }

    /**
     * Collects the sh.zolt.* imports of each main source file, grouped so that an
     * undeclared edge can be reported with a concrete example file.
     */
    static List<ImportSite> zoltImports(List<Path> sourceRoots) throws IOException {
        List<ImportSite> imports = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            String module = moduleName(sourceRoot);
            for (Path javaFile : ArchitectureSourceFiles.javaFiles(List.of(sourceRoot))) {
                for (String line : Files.readAllLines(javaFile)) {
                    Matcher matcher = IMPORT_PATTERN.matcher(line);
                    if (!matcher.matches()) {
                        continue;
                    }
                    String imported = matcher.group(1);
                    if (imported.startsWith("sh.zolt.")) {
                        imports.add(new ImportSite(module, imported, javaFile));
                    }
                }
            }
        }
        return List.copyOf(imports);
    }

    /**
     * Resolves the owning module of an imported sh.zolt.* reference at the type
     * level. Walks from the longest matching declared type down so nested-type
     * imports (sh.zolt.x.Outer.Inner) resolve to whoever declares Outer; returns
     * empty for references no module declares (e.g. wildcard package imports of a
     * package that holds no primary type by that name).
     */
    static Optional<String> resolveOwner(String importedReference, Map<String, String> typeOwners) {
        String[] parts = importedReference.split("\\.");
        for (int length = parts.length; length >= 3; length--) {
            String candidate = String.join(".", List.of(parts).subList(0, length));
            String owner = typeOwners.get(candidate);
            if (owner != null) {
                return Optional.of(owner);
            }
        }
        return Optional.empty();
    }

    /**
     * Reads the workspace dependency module names declared in a module's main
     * compile sections. Both [dependencies] and [dependencies.api] are explicit
     * compile-time module edges; provided, test, and processor sections are not.
     * A final-language workspace edge carries no path, so the module name comes
     * from the artifact half of its sh.zolt:&lt;module&gt; coordinate.
     *
     * <p>The guard's subject is whether an edge is declared at all, not which
     * language spells it, so the pre-conversion repository spellings
     * ([dependencies.api] and {@code workspace = "<path>"}) read as the same edge
     * as the final ones. The repository converts to the final language atomically
     * in one commit (design §21.2); until then its own manifests are the legacy
     * shape the pinned bootstrap engine parses.
     */
    static Set<String> declaredWorkspaceDependencies(Path moduleRoot) throws IOException {
        Path config = moduleRoot.resolve("zolt.toml");
        Set<String> dependencies = new TreeSet<>();
        if (!Files.isRegularFile(config)) {
            return dependencies;
        }
        boolean inDependencies = false;
        for (String line : Files.readAllLines(config)) {
            Matcher header = SECTION_HEADER_PATTERN.matcher(line);
            if (header.matches()) {
                inDependencies = COMPILE_DEPENDENCY_SECTIONS.contains(header.group(1));
                continue;
            }
            if (!inDependencies) {
                continue;
            }
            Matcher dependency = WORKSPACE_DEP_PATTERN.matcher(line);
            if (dependency.find()) {
                dependencies.add(dependency.group(1));
            }
        }
        return dependencies;
    }

    /**
     * Derives a module's directory name, which is also the artifact half of the
     * sh.zolt:&lt;module&gt; coordinate its consumers declare, from a src/main/java source root.
     */
    static String moduleName(Path sourceRoot) {
        // sourceRoot ends with <module>/src/main/java; the module directory name owns the module.
        Path normalized = sourceRoot.toAbsolutePath().normalize();
        Path moduleDir = normalized.getParent(); // .../<module>/src/main
        for (int i = 0; i < 2 && moduleDir != null; i++) {
            moduleDir = moduleDir.getParent();
        }
        if (moduleDir == null) {
            throw new IllegalStateException("Could not derive module name from source root " + sourceRoot);
        }
        return moduleDir.getFileName().toString();
    }

    /** The module directory ( .../<module> ) for a src/main/java source root. */
    static Path moduleRoot(Path sourceRoot) {
        Path normalized = sourceRoot.toAbsolutePath().normalize();
        Path moduleDir = normalized.getParent(); // .../<module>/src/main
        for (int i = 0; i < 2 && moduleDir != null; i++) {
            moduleDir = moduleDir.getParent();
        }
        if (moduleDir == null) {
            throw new IllegalStateException("Could not derive module root from source root " + sourceRoot);
        }
        return moduleDir;
    }

    private static Optional<String> packageDeclaration(Path javaFile) throws IOException {
        for (String line : Files.readAllLines(javaFile)) {
            Matcher matcher = PACKAGE_PATTERN.matcher(line);
            if (matcher.matches()) {
                String name = matcher.group(1);
                return name.startsWith("sh.zolt.") ? Optional.of(name) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static String primaryTypeName(Path javaFile) {
        String fileName = javaFile.getFileName().toString();
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - ".java".length()) : fileName;
    }

    record ImportSite(String module, String importedReference, Path file) {
    }
}
