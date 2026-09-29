package sh.zolt.explain.maven;

import static sh.zolt.explain.maven.MavenXml.child;

import sh.zolt.explain.SourceTreeEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.w3c.dom.Element;

/** Project-level evidence used to decide whether a Maven Kotlin draft is lossless. */
record MavenKotlinProjectEvidence(
        String compilerRelease,
        String compilerProc,
        String sourceEncoding,
        List<String> compilerProperties,
        boolean explicitSourceDirectory,
        boolean explicitTestSourceDirectory,
        boolean groovyTestSourcesPresent,
        boolean modularSources,
        boolean sourceLinksPresent) {
    MavenKotlinProjectEvidence {
        compilerProperties = List.copyOf(compilerProperties);
    }

    static MavenKotlinProjectEvidence inspect(
            Element project,
            Path projectDirectory,
            MavenPomProperties properties,
            List<String> sourceRoots,
            List<String> testSourceRoots) {
        SourceTreeEvidence sourceTree = SourceTreeEvidence.inspect(
                "Maven",
                projectDirectory,
                sourceRoots,
                testSourceRoots);
        return new MavenKotlinProjectEvidence(
                properties.interpolate(properties.values().get("maven.compiler.release")).strip(),
                properties.interpolate(properties.values().get("maven.compiler.proc")).strip(),
                properties.interpolate(properties.values().get("project.build.sourceEncoding")).strip(),
                compilerProperties(properties),
                buildElement(project, "sourceDirectory"),
                buildElement(project, "testSourceDirectory"),
                Files.isDirectory(projectDirectory.resolve("src/test/groovy")),
                sourceTree.modularSources(),
                sourceTree.sourceLinksPresent());
    }

    private static List<String> compilerProperties(MavenPomProperties properties) {
        return properties.values().keySet().stream()
                .filter(name -> name.startsWith("maven.compiler.")
                        || name.equals("project.build.sourceEncoding")
                        || name.equals("encoding")
                        || name.equals("maven.main.skip")
                        || name.equals("maven.test.skip"))
                .sorted()
                .toList();
    }

    private static boolean buildElement(Element project, String name) {
        return child(project, "build").flatMap(build -> child(build, name)).isPresent();
    }

}
