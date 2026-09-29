package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.text;

import java.nio.file.Path;
import org.w3c.dom.Element;

/** Statically resolved Maven artifact identity and its provenance. */
record MavenArtifactIdentity(String value, boolean fixed) {
    static MavenArtifactIdentity inspect(
            Element project,
            Path projectDirectory,
            MavenPomProperties properties) {
        String declared = text(project, "artifactId").orElse("");
        String resolved = properties.interpolate(declared).strip();
        boolean fixed = !resolved.isBlank() && !resolved.contains("${");
        String value = declared.isBlank()
                ? projectDirectory.getFileName().toString()
                : resolved;
        return new MavenArtifactIdentity(value, fixed);
    }
}
