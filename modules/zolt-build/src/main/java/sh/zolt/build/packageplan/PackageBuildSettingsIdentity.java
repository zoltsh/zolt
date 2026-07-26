package sh.zolt.build.packageplan;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.GeneratedSourceStep;
import java.util.Comparator;
import java.util.List;

final class PackageBuildSettingsIdentity {
    private PackageBuildSettingsIdentity() {
    }

    static String main(BuildSettings build) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-main-build-settings.v1");
        hash.value("sourceRoots", build.sourceRoots().toString());
        hash.value("outputRoot", build.outputRoot());
        hash.value("output", build.output());
        hash.value("resourceRoots", build.resourceRoots().toString());
        hash.value(
                "resourceFilteringEnabled",
                Boolean.toString(build.resourceFiltering().enabled()));
        hash.value("resourceFilteringIncludes", build.resourceFiltering().includes().toString());
        hash.value(
                "resourceFilteringMissing",
                build.resourceFiltering().missing().toString());
        hash.value("resourceFilteringTokens", build.resourceFiltering().tokens().toString());
        hash.value("metadata", build.metadata().toString());
        generatedSteps(hash, "main", build.generatedMainSources());
        return hash.finish();
    }

    static String test(BuildSettings build) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-test-build-settings.v1");
        hash.value("test", build.test());
        hash.value("outputRoot", build.outputRoot());
        hash.value("testOutput", build.testOutput());
        hash.value("testSources", build.testSources().toString());
        hash.value("groovyTestSources", build.groovyTestSources().toString());
        hash.value("integrationTestOutput", build.integrationTestOutput());
        hash.value("integrationTestSources", build.integrationTestSources().toString());
        hash.value(
                "integrationTestResourceRoots",
                build.integrationTestResourceRoots().toString());
        hash.value("testResourceRoots", build.testResourceRoots().toString());
        hash.value(
                "testResourceFilteringEnabled",
                Boolean.toString(build.resourceFiltering().testEnabled()));
        hash.value("resourceFilteringIncludes", build.resourceFiltering().includes().toString());
        hash.value(
                "resourceFilteringMissing",
                build.resourceFiltering().missing().toString());
        hash.value("resourceFilteringTokens", build.resourceFiltering().tokens().toString());
        hash.value("testRuntime", build.testRuntime().toString());
        hash.value("testSuites", build.testSuites().toString());
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
