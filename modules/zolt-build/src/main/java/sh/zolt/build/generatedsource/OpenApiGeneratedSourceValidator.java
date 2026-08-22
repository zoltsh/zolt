package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.OpenApiGeneratedSourcePaths.inputPath;

import sh.zolt.build.BuildException;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import java.nio.file.Files;
import java.nio.file.Path;

final class OpenApiGeneratedSourceValidator {
    private OpenApiGeneratedSourceValidator() {
    }

    static void validateStep(Path projectRoot, String scope, GeneratedSourceStep step) {
        if (!"java".equals(step.language())) {
            throw new BuildException(
                    "OpenAPI generated source step [generated."
                            + scope
                            + "."
                            + step.id()
                            + "] uses unsupported language `"
                            + step.language()
                            + "`. Zolt currently supports java.");
        }
        if (step.inputs().size() != 1) {
            throw new BuildException(
                    "OpenAPI generated source step [generated."
                            + scope
                            + "."
                            + step.id()
                            + "] requires exactly one input spec.");
        }
        Path input = inputPath(projectRoot, step.inputs().getFirst(), scope, step.id(), "input");
        if (!Files.isRegularFile(input)) {
            throw new BuildException(
                    "OpenAPI input "
                            + step.inputs().getFirst()
                            + " does not exist for [generated."
                            + scope
                            + "."
                            + step.id()
                            + "]. Add the file or remove the generated-source step.");
        }
        OpenApiGenerationSettings settings = step.openApi();
        if (settings.toolCoordinate().isEmpty() || settings.toolVersion().isEmpty()) {
            throw new BuildException(
                    "OpenAPI generation requires [generated.tools.openapi].coordinate and version. "
                            + "Add org.openapitools:openapi-generator-cli with version or versionRef, run `zolt resolve`, then retry.");
        }
        if (settings.generator().isEmpty()) {
            throw new BuildException(
                    "OpenAPI generated source step [generated."
                            + scope
                            + "."
                            + step.id()
                            + "] requires generator or preset.generator.");
        }
        settings.config().ifPresent(value -> requireFile(projectRoot, value, scope, step.id(), "config"));
        settings.templateDir().ifPresent(value -> requireDirectory(projectRoot, value, scope, step.id(), "templateDir"));
    }

    private static void requireFile(Path projectRoot, String value, String scope, String id, String field) {
        Path path = inputPath(projectRoot, value, scope, id, field);
        if (!Files.isRegularFile(path)) {
            throw new BuildException(
                    "OpenAPI "
                            + field
                            + " `"
                            + value
                            + "` does not exist for [generated."
                            + scope
                            + "."
                            + id
                            + "]. Add the file or update the generated-source step.");
        }
    }

    private static void requireDirectory(Path projectRoot, String value, String scope, String id, String field) {
        Path path = inputPath(projectRoot, value, scope, id, field);
        if (!Files.isDirectory(path)) {
            throw new BuildException(
                    "OpenAPI "
                            + field
                            + " `"
                            + value
                            + "` does not exist for [generated."
                            + scope
                            + "."
                            + id
                            + "]. Add the directory or update the generated-source step.");
        }
    }
}
