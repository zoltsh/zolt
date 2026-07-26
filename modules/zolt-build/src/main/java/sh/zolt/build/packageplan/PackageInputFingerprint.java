package sh.zolt.build.packageplan;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.List;

/**
 * Builds the schema-v2 identity of every current input used to choose package bytes and outputs.
 */
final class PackageInputFingerprint {
    private static final String SCHEMA = "zolt.package-input.v2";

    private PackageInputFingerprint() {
    }

    static PackagePlanEvidence evidence(
            Path projectRoot,
            ProjectConfig config,
            ZoltLockfile lockfile,
            String frameworkRulesIdentity,
            Path archivePath,
            Path applicationOutput,
            String applicationLayout,
            List<PackagePlanDependency> dependencies,
            List<PackagePlanOutput> outputs,
            String buildInputFingerprint,
            String applicationOutputFingerprint,
            List<PackagePlanLiveInput> supplementalInputs,
            List<PackagePlanWorkspaceInput> workspaceInputs,
            List<PackagePlanMaterializedInput> materializedInputs) {
        String packageLockFingerprint = packageLockFingerprint(lockfile);
        String resolutionFingerprint = resolutionFingerprint(lockfile);
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", SCHEMA);
        hash.value("mode", config.packageSettings().mode().configValue());
        hash.value("project", config.project().toString());
        hash.value(
                "buildMain",
                PackageBuildSettingsIdentity.main(config.build()));
        hash.value("package", config.packageSettings().toString());
        hash.value("framework", config.frameworkSettings().toString());
        hash.value(
                "compilerMain",
                PackageCompilerSettingsIdentity.main(
                        config.compilerSettings()));
        if (outputs.stream().anyMatch(output -> "tests".equals(output.kind()))) {
            hash.value(
                    "buildTest",
                    PackageBuildSettingsIdentity.test(config.build()));
            hash.value(
                    "compilerTest",
                    PackageCompilerSettingsIdentity.test(
                            config.compilerSettings()));
        }
        hash.value("apiDependencies", config.apiDependencies().toString());
        hash.value("dependencies", config.dependencies().toString());
        hash.value("runtimeDependencies", config.runtimeDependencies().toString());
        hash.value("providedDependencies", config.providedDependencies().toString());
        hash.value("workspaceApiDependencies", config.workspaceApiDependencies().toString());
        hash.value("workspaceDependencies", config.workspaceDependencies().toString());
        hash.value("dependencyMetadata", config.dependencyMetadata().toString());
        hash.value("dependencyPolicy", config.dependencyPolicy().toString());
        hash.value("packageLock", packageLockFingerprint);
        hash.value("resolution", resolutionFingerprint);
        hash.value("frameworkRules", frameworkRulesIdentity);
        hash.value("archive", display(projectRoot, archivePath));
        hash.value("applicationOutput", display(projectRoot, applicationOutput));
        hash.value("applicationLayout", applicationLayout);
        hash.value("buildInput", buildInputFingerprint);
        hash.value("applicationOutputBytes", applicationOutputFingerprint);
        for (PackagePlanDependency dependency : dependencies) {
            hash.value("dependency", dependency.toString());
        }
        for (PackagePlanOutput output : outputs) {
            hash.value(
                    "output",
                    output.kind()
                            + "\t"
                            + display(projectRoot, output.path())
                            + "\t"
                            + output.checksumKind()
                            + "\t"
                            + output.artifactType());
        }
        for (PackagePlanLiveInput input : supplementalInputs) {
            hash.value("supplementalInput", input.toString());
        }
        for (PackagePlanWorkspaceInput input : workspaceInputs) {
            hash.value(
                    "workspaceInput",
                    input.coordinate()
                            + "\t"
                            + input.identity()
                            + "\t"
                            + input.fingerprint());
        }
        for (PackagePlanMaterializedInput input : materializedInputs) {
            hash.value(
                    "materializedInput",
                    input.coordinate()
                            + "\t"
                            + input.sourceIdentity()
                            + "\t"
                            + input.sourceFingerprint()
                            + "\t"
                            + display(projectRoot, input.jarPath()));
        }
        return new PackagePlanEvidence(
                hash.finish(),
                buildInputFingerprint,
                applicationOutputFingerprint,
                packageLockFingerprint,
                resolutionFingerprint,
                frameworkRulesIdentity,
                outputs,
                supplementalInputs,
                workspaceInputs,
                materializedInputs);
    }

    static String packageLockFingerprint(ZoltLockfile lockfile) {
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.package-lock-input.v2");
        hash.value("version", Integer.toString(lockfile.version()));
        hash.value("aliasFingerprint", lockfile.aliasFingerprint().orElse(""));
        hash.value(
                "projectResolutionFingerprint",
                lockfile.projectResolutionFingerprint().orElse(""));
        lockfile.projectResolutionInputFingerprints().stream()
                .sorted()
                .forEach(value -> hash.value("resolutionInput", value));
        lockfile.packages().stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> hash.value("package", value));
        lockfile.conflicts().stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> hash.value("conflict", value));
        lockfile.policyEffects().stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> hash.value("policyEffect", value));
        lockfile.memberGraphs().stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> hash.value("memberGraph", value));
        return hash.finish();
    }

    private static String resolutionFingerprint(ZoltLockfile lockfile) {
        if (lockfile.projectResolutionFingerprint().isPresent()) {
            return lockfile.projectResolutionFingerprint().orElseThrow();
        }
        PackageCanonicalHash hash = new PackageCanonicalHash();
        hash.value("schema", "zolt.project-resolution-input.v1");
        lockfile.projectResolutionInputFingerprints().stream()
                .sorted()
                .forEach(value -> hash.value("input", value));
        lockfile.packages().stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> hash.value("package", value));
        lockfile.memberGraphs().stream()
                .map(Object::toString)
                .sorted()
                .forEach(value -> hash.value("memberGraph", value));
        return hash.finish();
    }

    private static String display(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(normalizedRoot)
                ? normalizedRoot.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString().replace('\\', '/');
    }

}
