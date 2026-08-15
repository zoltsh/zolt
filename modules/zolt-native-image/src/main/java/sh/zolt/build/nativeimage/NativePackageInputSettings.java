package sh.zolt.build.nativeimage;

import sh.zolt.project.BuildSettings;

/** Derives native-private build and package paths without changing the configured project. */
final class NativePackageInputSettings {
    private NativePackageInputSettings() {
    }

    static BuildSettings withOutputRoot(BuildSettings build, String outputRoot) {
        return new BuildSettings(
                build.source(),
                build.sourceRoots(),
                build.test(),
                outputRoot,
                build.output(),
                build.testOutput(),
                build.testSources(),
                build.groovyTestSources(),
                build.integrationTestOutput(),
                build.integrationTestSources(),
                build.integrationTestResourceRoots(),
                build.resourceRoots(),
                build.testResourceRoots(),
                build.resourceFiltering(),
                build.testRuntime(),
                build.testSuites(),
                build.metadata(),
                build.generatedMainSources(),
                build.generatedTestSources());
    }
}
