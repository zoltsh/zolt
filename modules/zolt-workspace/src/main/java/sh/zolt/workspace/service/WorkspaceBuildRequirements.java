package sh.zolt.workspace.service;

public record WorkspaceBuildRequirements(
        boolean mainCompileClasspath,
        boolean mainRuntimeClasspath,
        boolean testCompileClasspath,
        boolean testRuntimeClasspath,
        boolean processorClasspath,
        boolean testProcessorClasspath,
        boolean packageInputs) {
    public WorkspaceBuildRequirements {
        if (!mainCompileClasspath) {
            throw new IllegalArgumentException("Workspace builds require the main compile classpath.");
        }
        if (testRuntimeClasspath && !testCompileClasspath) {
            throw new IllegalArgumentException("Test runtime requires the test compile classpath.");
        }
        if (testProcessorClasspath && !testCompileClasspath) {
            throw new IllegalArgumentException("Test processors require the test compile classpath.");
        }
    }

    public static WorkspaceBuildRequirements mainBuild() {
        return new WorkspaceBuildRequirements(
                true,
                false,
                false,
                false,
                true,
                false,
                false);
    }

    public static WorkspaceBuildRequirements testCompile() {
        return new WorkspaceBuildRequirements(
                true,
                false,
                true,
                false,
                true,
                true,
                false);
    }

    public static WorkspaceBuildRequirements runtime() {
        return new WorkspaceBuildRequirements(
                true,
                true,
                false,
                false,
                true,
                false,
                false);
    }

    public static WorkspaceBuildRequirements testRun() {
        return new WorkspaceBuildRequirements(
                true,
                true,
                true,
                true,
                true,
                true,
                false);
    }

    public static WorkspaceBuildRequirements packaging() {
        return new WorkspaceBuildRequirements(
                true,
                true,
                false,
                false,
                true,
                false,
                true);
    }

    public static WorkspaceBuildRequirements nativeBuild() {
        return new WorkspaceBuildRequirements(
                true,
                true,
                false,
                false,
                true,
                false,
                true);
    }

    public WorkspaceBuildRequirements withPackageInputs(boolean required) {
        if (packageInputs == required) {
            return this;
        }
        return new WorkspaceBuildRequirements(
                mainCompileClasspath,
                mainRuntimeClasspath,
                testCompileClasspath,
                testRuntimeClasspath,
                processorClasspath,
                testProcessorClasspath,
                required);
    }
}
