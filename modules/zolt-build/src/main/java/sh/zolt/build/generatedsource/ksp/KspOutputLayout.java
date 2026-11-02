package sh.zolt.build.generatedsource.ksp;

import java.nio.file.Path;
import sh.zolt.build.BuildException;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;

/** The single owned KSP output root and its fixed compiler-facing lanes. */
record KspOutputLayout(
        Path baseDirectory,
        Path cachesDirectory,
        Path classOutputDirectory,
        Path kotlinOutputDirectory,
        Path javaOutputDirectory,
        Path resourceOutputDirectory) {
    static KspOutputLayout resolve(
            Path projectRoot,
            String scope,
            GeneratedSourceStep step) {
        if (step.kind() != GeneratedSourceKind.KSP) {
            throw new BuildException(
                    "KSP output layout requires a KSP generated source step, but `"
                            + step.id() + "` uses `" + step.kind().configValue() + "`.");
        }
        if (!"main".equals(scope) && !"test".equals(scope)) {
            throw new BuildException("KSP generated source scope must be `main` or `test`.");
        }
        Path base;
        try {
            base = ProjectPaths.output(
                    projectRoot,
                    "[generated." + scope + "." + step.id() + "].output",
                    step.output());
        } catch (ProjectPathException exception) {
            throw new BuildException(
                    "Invalid KSP output path `" + step.output() + "` for [generated."
                            + scope + "." + step.id() + "]. " + exception.getMessage(),
                    exception);
        }
        return new KspOutputLayout(
                base,
                base.resolve("cache"),
                base.resolve("classes"),
                base.resolve("kotlin"),
                base.resolve("java"),
                base.resolve("resources"));
    }
}
