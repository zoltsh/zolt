package sh.zolt.explain.emit;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.maven.MavenInspectionResult;
import java.util.List;

/**
 * Turns a Maven or Gradle audit into a ready-to-print draft: a single {@code zolt.toml} for a
 * single-project build, or a multi-document workspace bundle (root {@code [workspace]} plus one
 * labelled member document per module) for a reactor / multi-project build.
 *
 * <p>Groups the mapping and rendering collaborators behind one entry point so callers depend on a
 * single seam. The {@link AuthoredManifestRenderer} is injected by the CLI (it wraps the real
 * canonical writer), keeping zolt-explain free of a zolt-toml dependency.
 */
public final class EmitRenderer {
    private final InspectionToManifest mapper;
    private final DraftZoltTomlRenderer draftRenderer;
    private final DraftWorkspaceRenderer workspaceRenderer;
    private final AuthoredManifestRenderer manifestRenderer;

    public EmitRenderer(
            InspectionToManifest mapper,
            DraftZoltTomlRenderer draftRenderer,
            DraftWorkspaceRenderer workspaceRenderer,
            AuthoredManifestRenderer manifestRenderer) {
        this.mapper = mapper;
        this.draftRenderer = draftRenderer;
        this.workspaceRenderer = workspaceRenderer;
        this.manifestRenderer = manifestRenderer;
    }

    public String renderMaven(MavenInspectionResult result) {
        return render(mapper.emitFromMaven(result));
    }

    public String renderGradle(GradleInspectionResult result) {
        return render(mapper.emitFromGradle(result));
    }

    public List<DraftZoltTomlDocument> renderMavenDocuments(MavenInspectionResult result) {
        return renderDocuments(mapper.emitFromMaven(result));
    }

    public List<DraftZoltTomlDocument> renderGradleDocuments(GradleInspectionResult result) {
        return renderDocuments(mapper.emitFromGradle(result));
    }

    private String render(DraftEmit emit) {
        if (emit instanceof DraftWorkspace workspace) {
            return workspaceRenderer.render(workspace, manifestRenderer);
        }
        return draftRenderer.render((DraftZoltToml) emit, manifestRenderer);
    }

    private List<DraftZoltTomlDocument> renderDocuments(DraftEmit emit) {
        if (emit instanceof DraftWorkspace workspace) {
            return workspaceRenderer.renderDocuments(workspace, manifestRenderer);
        }
        return List.of(new DraftZoltTomlDocument(
                "zolt.toml",
                withTrailingNewline(draftRenderer.render((DraftZoltToml) emit, manifestRenderer))));
    }

    private static String withTrailingNewline(String value) {
        return value.stripTrailing() + "\n";
    }
}
