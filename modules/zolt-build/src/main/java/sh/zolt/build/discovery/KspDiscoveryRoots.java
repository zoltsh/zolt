package sh.zolt.build.discovery;

import java.nio.file.Files;
import java.nio.file.Path;
import sh.zolt.build.SourceDiscoveryException;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;

/** Resolves the two compiler-facing source lanes beneath one published KSP output root. */
record KspDiscoveryRoots(
        Path javaRoot,
        String javaKey,
        Path kotlinRoot,
        String kotlinKey) {
    static KspDiscoveryRoots resolve(
            Path projectRoot,
            String scope,
            GeneratedSourceStep step) {
        if (step.kind() != GeneratedSourceKind.KSP) {
            throw new IllegalArgumentException("KSP discovery roots require a KSP step.");
        }
        String key = "[generated." + scope + "." + step.id() + "].output";
        Path base;
        try {
            base = ProjectPaths.output(projectRoot, key, step.output());
        } catch (ProjectPathException exception) {
            throw new SourceDiscoveryException(
                    "Invalid generated source output path `" + step.output() + "` for [generated."
                            + scope + "." + step.id() + "]. " + exception.getMessage(),
                    exception);
        }
        if (!Files.isDirectory(base) && step.required()) {
            throw new SourceDiscoveryException(
                    "KSP generated source root `" + step.output()
                            + "` is missing. Run KSP generation before source discovery.");
        }
        return new KspDiscoveryRoots(
                base.resolve("java"),
                key + ".java",
                base.resolve("kotlin"),
                key + ".kotlin");
    }
}
