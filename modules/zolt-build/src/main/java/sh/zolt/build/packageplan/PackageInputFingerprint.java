package sh.zolt.build.packageplan;

import sh.zolt.build.PackageException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
            List<PackagePlanOutput> outputs) {
        String packageLockFingerprint = packageLockFingerprint(lockfile);
        String resolutionFingerprint = resolutionFingerprint(lockfile);
        CanonicalHash hash = new CanonicalHash();
        hash.value("schema", SCHEMA);
        hash.value("mode", config.packageSettings().mode().configValue());
        hash.value("project", config.project().toString());
        hash.value("build", config.build().toString());
        hash.value("package", config.packageSettings().toString());
        hash.value("framework", config.frameworkSettings().toString());
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
        for (PackagePlanDependency dependency : dependencies) {
            hash.value("dependency", dependency.toString());
        }
        for (PackagePlanOutput output : outputs) {
            hash.value(
                    "output",
                    output.kind() + "\t" + display(projectRoot, output.path()));
        }
        return new PackagePlanEvidence(
                hash.finish(),
                packageLockFingerprint,
                resolutionFingerprint,
                frameworkRulesIdentity,
                outputs);
    }

    private static String packageLockFingerprint(ZoltLockfile lockfile) {
        CanonicalHash hash = new CanonicalHash();
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
        CanonicalHash hash = new CanonicalHash();
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

    private static final class CanonicalHash {
        private final MessageDigest digest = sha256();

        void value(String key, String value) {
            update(key);
            update(value == null ? "" : value);
        }

        String finish() {
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        }

        private void update(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
            digest.update((byte) '\n');
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new PackageException(
                        "Could not fingerprint package inputs because SHA-256 is unavailable.",
                        exception);
            }
        }
    }
}
