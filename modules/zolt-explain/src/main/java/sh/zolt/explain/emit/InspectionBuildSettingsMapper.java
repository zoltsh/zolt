package sh.zolt.explain.emit;

import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.SourceRootLanguage;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredResources;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps audited migration roots into the authored {@code [build]} and {@code [resources]} domains.
 *
 * <p>Conventional roots are behavior, not boilerplate (design §5.1): a root list that already equals
 * the Zolt convention is dropped so the draft stays sparse. Only genuinely non-conventional roots are
 * authored, and a root the manifest path grammar cannot express becomes a review note.
 */
final class InspectionBuildSettingsMapper {
    private static final ManifestRelativePath MAIN_SOURCE = new ManifestRelativePath("src/main/java");
    private static final ManifestRelativePath TEST_SOURCE = new ManifestRelativePath("src/test/java");
    private static final ManifestRelativePath MAIN_RESOURCES =
            new ManifestRelativePath("src/main/resources");
    private static final ManifestRelativePath TEST_RESOURCES =
            new ManifestRelativePath("src/test/resources");

    private InspectionBuildSettingsMapper() {
    }

    static AuthoredBuildConfiguration fromRoots(
            List<String> sourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            List<String> notes) {
        return fromRoots(sourceRoots, testSourceRoots, List.of(), resourceRoots, testResourceRoots, notes);
    }

    static AuthoredBuildConfiguration fromRoots(
            List<String> sourceRoots,
            List<String> testSourceRoots,
            List<String> groovyTestSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            List<String> notes) {
        List<ManifestRelativePath> mainRoots = paths(sourceRoots, "a main source root", notes);
        List<ManifestRelativePath> testRoots = new ArrayList<>(
                paths(testSourceRoots, "a test source root", notes));
        testRoots.addAll(paths(groovyTestSourceRoots, "a test source root", notes));
        testRoots = distinct(testRoots);
        if (mainRoots.isEmpty()) {
            notes.add(unsupported(sourceRoots)
                    ? "Every audited main source root names a language Zolt cannot build; the draft"
                            + " keeps the Zolt convention `src/main/java`. Migrate a plain Java module"
                            + " first, then set [build].sources to its real source root."
                    : "No main source root was found by the static audit; the draft keeps the Zolt"
                            + " convention `src/main/java`. Set [build].sources to the real source root"
                            + " before building.");
        }
        if (!testRoots.isEmpty() && !List.of(TEST_SOURCE).equals(testRoots)) {
            notes.add(
                    "Test sources live outside the Zolt convention `src/test/java` (" + join(testRoots)
                            + "); Zolt derives the test root from the build convention, so move them or"
                            + " add a [tests] override by hand.");
        }
        Optional<AuthoredBuild> build = conventional(mainRoots, MAIN_SOURCE)
                ? Optional.empty()
                : Optional.of(new AuthoredBuild(mainRoots, Optional.empty(), Optional.empty()));

        List<ManifestRelativePath> mainResources =
                authored(resourceRootsFor(resourceRoots, "main", notes), MAIN_RESOURCES);
        List<ManifestRelativePath> testResources =
                authored(resourceRootsFor(testResourceRoots, "test", notes), TEST_RESOURCES);
        Optional<AuthoredResources> resources = mainResources.isEmpty() && testResources.isEmpty()
                ? Optional.empty()
                : Optional.of(new AuthoredResources(
                        mainResources, testResources, Optional.empty(), Map.of()));
        return new AuthoredBuildConfiguration(
                build, Optional.empty(), resources, Optional.empty(), Optional.empty());
    }

    /**
     * Audited resource roots minus a bare project root ({@code .} / {@code ./}). Such a root usually
     * comes from a Maven {@code <resource>} that only exists to package one file via
     * {@code <targetPath>}/{@code <includes>}; carrying it verbatim would turn the whole project tree
     * into a Zolt resource root and copy everything into the jar.
     */
    private static List<ManifestRelativePath> resourceRootsFor(
            List<String> roots, String scope, List<String> notes) {
        List<String> kept = new ArrayList<>();
        for (String root : roots == null ? List.<String>of() : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            if (isProjectRoot(root)) {
                notes.add(
                        "Maven declared a project-root `" + root.strip() + "` resource root in [resources]."
                                + scope
                                + "; it was dropped because it usually only packages a single file via"
                                + " <targetPath>/<includes>, and carrying it live would copy the whole"
                                + " project tree into the jar. Re-add a narrow resource root by hand if needed.");
                continue;
            }
            kept.add(root);
        }
        return paths(kept, "a " + scope + " resource root", notes);
    }

    private static boolean conventional(
            List<ManifestRelativePath> roots, ManifestRelativePath convention) {
        return roots.isEmpty() || List.of(convention).equals(roots);
    }

    /** The roots worth authoring: a conventional list is dropped so the draft stays sparse. */
    private static List<ManifestRelativePath> authored(
            List<ManifestRelativePath> roots, ManifestRelativePath convention) {
        return conventional(roots, convention) ? List.of() : roots;
    }

    /** Whether the audit found roots, but only for languages Zolt cannot build. */
    private static boolean unsupported(List<String> roots) {
        return roots != null && roots.stream()
                .filter(root -> root != null && !root.isBlank())
                .anyMatch(root -> SourceRootLanguage.unsupported(root.strip()).isPresent());
    }

    private static boolean isProjectRoot(String root) {
        String value = root.strip();
        return value.equals(".") || value.equals("./");
    }

    private static List<ManifestRelativePath> paths(
            List<String> values, String subject, List<String> notes) {
        List<ManifestRelativePath> paths = new ArrayList<>();
        if (values == null) {
            return paths;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String root = value.strip();
            Optional<SourceRootLanguage> unsupported = SourceRootLanguage.unsupported(root);
            if (unsupported.isPresent()) {
                // Emitting the root would produce a manifest the parser rejects (design §10.1), so the
                // audited reality is carried as review data instead.
                notes.add("The static audit reported " + subject + " at `" + root + "`, which Zolt"
                        + " cannot build: " + unsupported.orElseThrow().remedy());
                continue;
            }
            try {
                paths.add(new ManifestRelativePath(root));
            } catch (IllegalArgumentException exception) {
                notes.add("The static audit reported " + subject + " at `" + root
                        + "`, which is not a project-relative manifest path: " + exception.getMessage()
                        + " Add it by hand after moving it beneath the project.");
            }
        }
        return distinct(paths);
    }

    private static List<ManifestRelativePath> distinct(List<ManifestRelativePath> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String join(List<ManifestRelativePath> values) {
        return String.join(", ", values.stream().map(ManifestRelativePath::value).toList());
    }
}
