package sh.zolt.explain.emit;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.maven.MavenInspectionResult;

/**
 * Converts a static Maven or Gradle migration audit into a {@link DraftEmit}.
 *
 * <p>A single-project audit maps to a {@link DraftZoltToml}: Maven scopes map to Zolt dependency
 * sections (compile -&gt; dependencies, runtime -&gt; runtime, provided -&gt; provided, test -&gt;
 * test), imported BOMs map to platforms, and Gradle configurations map analogously. A multi-module
 * Maven reactor or Gradle multi-project build maps to a {@link DraftWorkspace}: a root
 * {@code [workspace]} document plus one member draft per module, with a dependency on a sibling
 * module rewritten to {@code { workspace = "<member-path>" }}. Anything the audit cannot map safely
 * (version-catalog aliases without a resolved coordinate, unresolved notations, Maven profiles,
 * custom repositories, unknown scopes) is returned as a review note rather than guessed into config.
 */
public final class InspectionToProjectConfig {
    /** Emits either a single {@link DraftZoltToml} or, for a reactor, a {@link DraftWorkspace}. */
    public DraftEmit emitFromMaven(MavenInspectionResult result) {
        requireProject(result.projects().isEmpty(), "Maven");
        if (MavenWorkspaceMapper.isWorkspace(result)) {
            return MavenWorkspaceMapper.map(result);
        }
        return MavenInspectionMapper.map(result);
    }

    /** Emits either a single {@link DraftZoltToml} or, for a multi-project, a {@link DraftWorkspace}. */
    public DraftEmit emitFromGradle(GradleInspectionResult result) {
        requireProject(result.projects().isEmpty(), "Gradle");
        if (GradleWorkspaceMapper.isWorkspace(result)) {
            return GradleWorkspaceMapper.map(result);
        }
        return GradleInspectionMapper.map(result);
    }

    public DraftZoltToml fromMaven(MavenInspectionResult result) {
        requireProject(result.projects().isEmpty(), "Maven");
        return MavenInspectionMapper.map(result);
    }

    public DraftZoltToml fromGradle(GradleInspectionResult result) {
        requireProject(result.projects().isEmpty(), "Gradle");
        return GradleInspectionMapper.map(result);
    }

    private static void requireProject(boolean empty, String tool) {
        if (empty) {
            throw new IllegalArgumentException(
                    "Cannot emit a draft zolt.toml: the " + tool + " audit found no project. "
                            + "Run zolt explain from a " + tool + " project root.");
        }
    }
}
