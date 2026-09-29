package sh.zolt.ide;

import static sh.zolt.ide.IdeJsonFields.comma;
import static sh.zolt.ide.IdeJsonFields.field;
import static sh.zolt.ide.IdeJsonFields.indent;
import static sh.zolt.ide.IdeJsonFields.pathArrayField;
import static sh.zolt.ide.IdeJsonFields.pathField;
import static sh.zolt.ide.IdeJsonFields.string;
import static sh.zolt.ide.IdeJsonFields.stringArrayField;
import static sh.zolt.ide.IdeJsonFields.stringField;
import static sh.zolt.ide.IdeJsonFields.stringMap;

import java.util.List;

public final class IdeModelJsonWriter {
    public String write(IdeModel model) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, 1, "schemaVersion", model.schemaVersion(), true);
        project(json, model.project());
        comma(json);
        java(json, model.java());
        comma(json);
        compiler(json, model.compiler());
        comma(json);
        testRuntime(json, model.testRuntime());
        comma(json);
        packageInfo(json, model.packageInfo());
        comma(json);
        paths(json, model.paths());
        comma(json);
        sourceRoots(json, model.sourceRoots());
        comma(json);
        generatedSources(json, model.generatedSources());
        comma(json);
        resourceRoots(json, model.resourceRoots());
        comma(json);
        outputs(json, model.outputs());
        comma(json);
        dependencies(json, model.dependencies());
        comma(json);
        classpaths(json, model.classpaths());
        comma(json);
        frameworks(json, model.frameworks());
        comma(json);
        diagnostics(json, model.diagnostics());
        json.append("\n}\n");
        return json.toString();
    }

    private static void project(StringBuilder json, IdeModel.ProjectInfo project) {
        indent(json, 1).append("\"project\": {\n");
        stringField(json, 2, "name", project.name(), true);
        stringField(json, 2, "group", project.group(), true);
        stringField(json, 2, "version", project.version(), true);
        stringField(json, 2, "mainClass", project.mainClass(), false);
        indent(json, 1).append("}");
    }

    private static void java(StringBuilder json, IdeModel.JavaInfo java) {
        indent(json, 1).append("\"java\": {\n");
        stringField(json, 2, "version", java.version(), true);
        stringField(json, 2, "detectedVersion", java.detectedVersion(), true);
        stringField(json, 2, "javaHome", java.javaHome(), false);
        indent(json, 1).append("}");
    }

    private static void compiler(StringBuilder json, IdeModel.CompilerInfo compiler) {
        indent(json, 1).append("\"compiler\": {\n");
        stringField(json, 2, "release", compiler.release(), true);
        stringField(json, 2, "effectiveRelease", compiler.effectiveRelease(), true);
        stringField(json, 2, "encoding", compiler.encoding(), true);
        stringArrayField(json, 2, "args", compiler.args(), true);
        stringArrayField(json, 2, "testArgs", compiler.testArgs(), true);
        pathField(json, 2, "generatedSources", compiler.generatedSources(), true);
        pathField(json, 2, "generatedTestSources", compiler.generatedTestSources(), false);
        indent(json, 1).append("}");
    }

    private static void testRuntime(StringBuilder json, IdeModel.TestRuntimeInfo testRuntime) {
        indent(json, 1).append("\"testRuntime\": {\n");
        stringArrayField(json, 2, "jvmArgs", testRuntime.jvmArgs(), true);
        stringMap(json, 2, "systemProperties", testRuntime.systemProperties(), true);
        stringMap(json, 2, "environment", testRuntime.environment(), true);
        stringArrayField(json, 2, "events", testRuntime.events(), false);
        indent(json, 1).append("}");
    }

    private static void packageInfo(StringBuilder json, IdeModel.PackageInfo packageInfo) {
        indent(json, 1).append("\"package\": {\n");
        stringField(json, 2, "mode", packageInfo.mode(), true);
        field(json, 2, "sources", packageInfo.sources(), true);
        field(json, 2, "javadoc", packageInfo.javadoc(), true);
        field(json, 2, "tests", packageInfo.tests(), true);
        pathField(json, 2, "mainJar", packageInfo.mainJar(), true);
        pathField(json, 2, "sourcesJar", packageInfo.sourcesJar(), true);
        pathField(json, 2, "javadocJar", packageInfo.javadocJar(), true);
        pathField(json, 2, "testsJar", packageInfo.testsJar(), true);
        publication(json, packageInfo.metadata());
        comma(json);
        stringMap(json, 2, "manifestAttributes", packageInfo.manifestAttributes(), false);
        indent(json, 1).append("}");
    }

    private static void publication(StringBuilder json, IdeModel.PublicationInfo publication) {
        indent(json, 2).append("\"metadata\": {\n");
        stringField(json, 3, "name", publication.name(), true);
        stringField(json, 3, "description", publication.description(), true);
        stringField(json, 3, "url", publication.url(), true);
        stringField(json, 3, "license", publication.license(), true);
        stringArrayField(json, 3, "developers", publication.developers(), true);
        stringField(json, 3, "scm", publication.scm(), true);
        stringField(json, 3, "issues", publication.issues(), false);
        indent(json, 2).append("}");
    }

    private static void paths(StringBuilder json, IdeModel.PathInfo paths) {
        indent(json, 1).append("\"paths\": {\n");
        pathField(json, 2, "root", paths.root(), true);
        pathField(json, 2, "config", paths.config(), true);
        pathField(json, 2, "lockfile", paths.lockfile(), false);
        indent(json, 1).append("}");
    }

    private static void sourceRoots(StringBuilder json, List<IdeModel.SourceRoot> roots) {
        indent(json, 1).append("\"sourceRoots\": [");
        if (!roots.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < roots.size(); index++) {
                IdeModel.SourceRoot root = roots.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "id", root.id(), true);
                stringField(json, 3, "kind", root.kind(), true);
                stringField(json, 3, "language", root.language(), true);
                pathField(json, 3, "path", root.path(), true);
                field(json, 3, "generated", root.generated(), false);
                indent(json, 2).append("}");
                if (index < roots.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void generatedSources(StringBuilder json, List<IdeModel.GeneratedSourceInfo> generatedSources) {
        indent(json, 1).append("\"generatedSources\": [");
        if (!generatedSources.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < generatedSources.size(); index++) {
                IdeModel.GeneratedSourceInfo generatedSource = generatedSources.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "id", generatedSource.id(), true);
                stringField(json, 3, "sourceRootId", generatedSource.sourceRootId(), true);
                stringField(json, 3, "scope", generatedSource.scope(), true);
                stringField(json, 3, "kind", generatedSource.kind(), true);
                stringField(json, 3, "language", generatedSource.language(), true);
                pathField(json, 3, "output", generatedSource.output(), true);
                pathArrayField(json, 3, "inputs", generatedSource.inputs(), true);
                field(json, 3, "required", generatedSource.required(), true);
                field(json, 3, "clean", generatedSource.clean(), true);
                stringField(json, 3, "ownership", generatedSource.ownership(), true);
                stringField(json, 3, "compileLane", generatedSource.compileLane(), true);
                stringField(json, 3, "freshness", generatedSource.freshness(), true);
                field(json, 3, "outputExists", generatedSource.outputExists(), true);
                field(json, 3, "inputsPresent", generatedSource.inputsPresent(), true);
                stringField(json, 3, "toolArtifact", generatedSource.toolArtifact(), true);
                stringField(json, 3, "toolVersionRef", generatedSource.toolVersionRef(), true);
                stringField(json, 3, "toolFingerprint", generatedSource.toolFingerprint(), true);
                stringField(json, 3, "optionsFingerprint", generatedSource.optionsFingerprint(), false);
                indent(json, 2).append("}");
                if (index < generatedSources.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void resourceRoots(StringBuilder json, List<IdeModel.ResourceRoot> roots) {
        indent(json, 1).append("\"resourceRoots\": [");
        if (!roots.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < roots.size(); index++) {
                IdeModel.ResourceRoot root = roots.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "id", root.id(), true);
                stringField(json, 3, "kind", root.kind(), true);
                pathField(json, 3, "path", root.path(), false);
                indent(json, 2).append("}");
                if (index < roots.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void outputs(StringBuilder json, IdeModel.OutputInfo outputs) {
        indent(json, 1).append("\"outputs\": {\n");
        pathField(json, 2, "mainClasses", outputs.mainClasses(), true);
        pathField(json, 2, "testClasses", outputs.testClasses(), true);
        pathField(json, 2, "integrationTestClasses", outputs.integrationTestClasses(), true);
        pathField(json, 2, "package", outputs.packagePath(), false);
        indent(json, 1).append("}");
    }

    private static void dependencies(StringBuilder json, IdeModel.DependencyInfo dependencies) {
        indent(json, 1).append("\"dependencies\": {\n");
        stringMap(json, 2, "versionAliases", dependencies.versionAliases(), true);
        dependencyArrayField(json, 2, "api", dependencies.api(), true);
        dependencyArrayField(json, 2, "implementation", dependencies.implementation(), true);
        dependencyArrayField(json, 2, "runtime", dependencies.runtime(), true);
        dependencyArrayField(json, 2, "provided", dependencies.provided(), true);
        dependencyArrayField(json, 2, "dev", dependencies.dev(), true);
        dependencyArrayField(json, 2, "test", dependencies.test(), true);
        dependencyArrayField(json, 2, "annotationProcessors", dependencies.annotationProcessors(), true);
        dependencyArrayField(json, 2, "testAnnotationProcessors", dependencies.testAnnotationProcessors(), false);
        indent(json, 1).append("}");
    }

    private static void classpaths(StringBuilder json, IdeModel.ClasspathInfo classpaths) {
        indent(json, 1).append("\"classpaths\": {\n");
        pathArrayField(json, 2, "compile", classpaths.compile(), true);
        pathArrayField(json, 2, "runtime", classpaths.runtime(), true);
        pathArrayField(json, 2, "test", classpaths.test(), true);
        pathArrayField(json, 2, "integrationTest", classpaths.integrationTest(), true);
        pathArrayField(json, 2, "processor", classpaths.processor(), true);
        pathArrayField(json, 2, "testProcessor", classpaths.testProcessor(), true);
        pathArrayField(json, 2, "quarkusDeployment", classpaths.quarkusDeployment(), false);
        indent(json, 1).append("}");
    }

    private static void frameworks(StringBuilder json, IdeModel.FrameworkInfo frameworks) {
        indent(json, 1).append("\"frameworks\": {\n");
        quarkus(json, frameworks.quarkus());
        indent(json, 1).append("}");
    }

    private static void quarkus(StringBuilder json, IdeModel.QuarkusInfo quarkus) {
        indent(json, 2).append("\"quarkus\": {\n");
        field(json, 3, "enabled", quarkus.enabled(), true);
        stringField(json, 3, "packageMode", quarkus.packageMode(), true);
        stringField(json, 3, "augmentationStatus", quarkus.augmentationStatus(), true);
        stringField(json, 3, "inputFingerprint", quarkus.inputFingerprint(), true);
        stringField(json, 3, "recordedInputFingerprint", quarkus.recordedInputFingerprint(), true);
        pathField(json, 3, "augmentationMetadata", quarkus.augmentationMetadata(), true);
        pathField(json, 3, "augmentationDirectory", quarkus.augmentationDirectory(), true);
        pathField(json, 3, "packageDirectory", quarkus.packageDirectory(), true);
        pathField(json, 3, "runnerJar", quarkus.runnerJar(), true);
        pathField(json, 3, "generatedBytecodeJar", quarkus.generatedBytecodeJar(), true);
        pathField(json, 3, "transformedBytecodeJar", quarkus.transformedBytecodeJar(), true);
        quarkusGeneratedOutputs(json, quarkus.generatedOutputs());
        comma(json);
        pathArrayField(json, 3, "deploymentClasspath", quarkus.deploymentClasspath(), false);
        indent(json, 2).append("}\n");
    }

    private static void quarkusGeneratedOutputs(
            StringBuilder json,
            List<IdeModel.QuarkusGeneratedOutput> generatedOutputs) {
        indent(json, 3).append("\"generatedOutputs\": [");
        if (!generatedOutputs.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < generatedOutputs.size(); index++) {
                IdeModel.QuarkusGeneratedOutput output = generatedOutputs.get(index);
                indent(json, 4).append("{\n");
                stringField(json, 5, "id", output.id(), true);
                stringField(json, 5, "kind", output.kind(), true);
                pathField(json, 5, "path", output.path(), true);
                field(json, 5, "exists", output.exists(), false);
                indent(json, 4).append("}");
                if (index < generatedOutputs.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 3);
        }
        json.append("]");
    }

    private static void diagnostics(StringBuilder json, List<IdeModel.Diagnostic> diagnostics) {
        indent(json, 1).append("\"diagnostics\": [");
        if (!diagnostics.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < diagnostics.size(); index++) {
                IdeModel.Diagnostic diagnostic = diagnostics.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "severity", diagnostic.severity(), true);
                stringField(json, 3, "code", diagnostic.code(), true);
                stringField(json, 3, "message", diagnostic.message(), true);
                pathField(json, 3, "path", diagnostic.path(), true);
                stringField(json, 3, "nextStep", diagnostic.nextStep(), false);
                indent(json, 2).append("}");
                if (index < diagnostics.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void dependencyArrayField(
            StringBuilder json,
            int level,
            String name,
            List<IdeModel.DependencyDeclaration> values,
            boolean trailingComma) {
        indent(json, level).append('"').append(name).append("\": [");
        if (!values.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < values.size(); index++) {
                IdeModel.DependencyDeclaration dependency = values.get(index);
                indent(json, level + 1).append("{\n");
                stringField(json, level + 2, "coordinate", dependency.coordinate(), true);
                stringField(json, level + 2, "version", dependency.version(), true);
                stringField(json, level + 2, "versionRef", dependency.versionRef(), true);
                field(json, level + 2, "managed", dependency.managed(), true);
                stringField(json, level + 2, "workspace", dependency.workspace(), true);
                field(json, level + 2, "optional", dependency.optional(), true);
                field(json, level + 2, "publishOnly", dependency.publishOnly(), true);
                stringArrayField(json, level + 2, "exclusions", dependency.exclusions(), false);
                indent(json, level + 1).append("}");
                if (index < values.size() - 1) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, level);
        }
        json.append("]");
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

}
