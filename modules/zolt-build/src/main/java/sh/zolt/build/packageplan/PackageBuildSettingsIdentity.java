package sh.zolt.build.packageplan;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import java.util.Comparator;
import java.util.List;

final class PackageBuildSettingsIdentity {
    private PackageBuildSettingsIdentity() {
    }

    static String canonical(BuildSettings build) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-build-settings.v1");
        hash.value("sourceRoots", build.sourceRoots().toString());
        hash.value("test", build.test());
        hash.value("outputRoot", build.outputRoot());
        hash.value("output", build.output());
        hash.value("testOutput", build.testOutput());
        hash.value("testSources", build.testSources().toString());
        hash.value("groovyTestSources", build.groovyTestSources().toString());
        hash.value("integrationTestOutput", build.integrationTestOutput());
        hash.value("integrationTestSources", build.integrationTestSources().toString());
        hash.value(
                "integrationTestResourceRoots",
                build.integrationTestResourceRoots().toString());
        hash.value("resourceRoots", build.resourceRoots().toString());
        hash.value("testResourceRoots", build.testResourceRoots().toString());
        hash.value("resourceFiltering", build.resourceFiltering().toString());
        hash.value("testRuntime", build.testRuntime().toString());
        hash.value("testSuites", build.testSuites().toString());
        hash.value("metadata", build.metadata().toString());
        generatedSteps(hash, "main", build.generatedMainSources());
        generatedSteps(hash, "test", build.generatedTestSources());
        return hash.finish();
    }

    private static void generatedSteps(
            PackageCanonicalHash hash,
            String scope,
            List<GeneratedSourceStep> steps) {
        steps.stream()
                .sorted(Comparator.comparing(GeneratedSourceStep::id)
                        .thenComparing(step -> step.kind().configValue()))
                .forEach(step -> hash.value(
                        "generatedStep",
                        scope
                                + "\t"
                                + step.id()
                                + "\t"
                                + step.kind().configValue()
                                + "\t"
                                + step.language()
                                + "\t"
                                + step.output()
                                + "\t"
                                + step.required()
                                + "\t"
                                + step.clean()));
    }
}
