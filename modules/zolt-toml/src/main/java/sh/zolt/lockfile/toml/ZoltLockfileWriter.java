package sh.zolt.lockfile.toml;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.VersionComparator;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockMemberGraph;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.lockfile.ZoltLockfile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class ZoltLockfileWriter {
    private final VersionComparator versionComparator = new VersionComparator();

    public void write(Path path, ZoltLockfile lockfile) {
        try {
            Files.writeString(path, write(lockfile));
        } catch (IOException exception) {
            throw new LockfileWriteException(
                    "Could not write zolt.lock at " + path + ". Check that the directory exists and is writable.",
                    exception);
        }
    }

    public String write(ZoltLockfile lockfile) {
        StringBuilder output = new StringBuilder();
        output.append("version = ").append(lockfile.version()).append('\n');
        lockfile.aliasFingerprint().ifPresent(value -> assignment(output, "aliasFingerprint", value));
        lockfile.projectResolutionFingerprint()
                .ifPresent(value -> assignment(output, "projectResolutionFingerprint", value));
        if (!lockfile.projectResolutionInputFingerprints().isEmpty()) {
            output.append("projectResolutionInputFingerprints = ");
            stringArray(output, sortedStrings(lockfile.projectResolutionInputFingerprints()));
            output.append('\n');
        }
        output.append('\n');
        for (LockPackage lockPackage : sortedPackages(lockfile.packages())) {
            writePackage(output, lockPackage);
        }
        for (LockMemberGraph memberGraph : sortedMemberGraphs(lockfile.memberGraphs())) {
            writeMemberGraph(output, memberGraph);
        }
        for (LockPolicyEffect policyEffect : sortedPolicyEffects(lockfile.policyEffects())) {
            writePolicyEffect(output, policyEffect);
        }
        for (LockConflict conflict : sortedConflicts(lockfile.conflicts())) {
            writeConflict(output, conflict);
        }
        return output.toString();
    }

    private void writePackage(StringBuilder output, LockPackage lockPackage) {
        output.append("[[package]]\n");
        assignment(output, "id", lockPackage.packageId().toString());
        assignment(output, "version", lockPackage.version());
        assignment(output, "source", lockPackage.source());
        assignment(output, "scope", lockPackage.scope().lockfileName());
        output.append("direct = ").append(lockPackage.direct()).append('\n');
        lockPackage.jar().ifPresent(value -> assignment(output, "jar", value));
        lockPackage.pom().ifPresent(value -> assignment(output, "pom", value));
        lockPackage.jarSha256().ifPresent(value -> assignment(output, "jarSha256", value));
        lockPackage.pomSha256().ifPresent(value -> assignment(output, "pomSha256", value));
        lockPackage.artifact().ifPresent(value -> assignment(output, "artifact", value));
        lockPackage.artifactType().ifPresent(value -> assignment(output, "artifactType", value));
        lockPackage.artifactSha256().ifPresent(value -> assignment(output, "artifactSha256", value));
        lockPackage.workspace().ifPresent(value -> assignment(output, "workspace", value));
        lockPackage.workspaceOutput().ifPresent(value -> assignment(output, "workspaceOutput", value));
        if (!lockPackage.members().isEmpty()) {
            output.append("members = ");
            stringArray(output, sortedStrings(lockPackage.members()));
            output.append('\n');
        }
        if (!lockPackage.exportedBy().isEmpty()) {
            output.append("exportedBy = ");
            stringArray(output, sortedStrings(lockPackage.exportedBy()));
            output.append('\n');
        }
        if (!lockPackage.policies().isEmpty()) {
            output.append("policies = ");
            stringArray(output, sortedStrings(lockPackage.policies()));
            output.append('\n');
        }
        if (!lockPackage.toolGroups().isEmpty()) {
            output.append("toolGroups = ");
            stringArray(output, sortedStrings(lockPackage.toolGroups()));
            output.append('\n');
        }
        output.append("dependencies = ");
        stringArray(output, sortedStrings(lockPackage.dependencies()));
        output.append("\n\n");
    }

    private void writePolicyEffect(StringBuilder output, LockPolicyEffect policyEffect) {
        output.append("[[policy]]\n");
        assignment(output, "kind", policyEffect.kind());
        assignment(output, "id", policyEffect.packageId().toString());
        policyEffect.requestedVersion().ifPresent(value -> assignment(output, "requested", value));
        policyEffect.source().ifPresent(value -> assignment(output, "source", value));
        assignment(output, "policy", policyEffect.policy());
        output.append('\n');
    }

    private void writeMemberGraph(StringBuilder output, LockMemberGraph memberGraph) {
        output.append("[[memberGraph]]\n");
        assignment(output, "member", memberGraph.member());
        assignment(output, "id", memberGraph.packageId().toString());
        assignment(output, "version", memberGraph.version());
        if (!memberGraph.variant().isDefault()) {
            assignment(output, "variant", memberGraph.variant().key());
        }
        assignment(output, "scope", memberGraph.scope().lockfileName());
        if (!memberGraph.policies().isEmpty()) {
            output.append("policies = ");
            stringArray(output, sortedStrings(memberGraph.policies()));
            output.append('\n');
        }
        output.append("dependencies = ");
        stringArray(output, sortedStrings(memberGraph.dependencies()));
        output.append("\n\n");
    }

    private void writeConflict(StringBuilder output, LockConflict conflict) {
        output.append("[[conflict]]\n");
        assignment(output, "id", conflict.packageId().toString());
        conflict.toolGroup().ifPresent(value -> assignment(output, "tool", value));
        conflict.variant()
                .filter(variant -> !variant.isDefault())
                .ifPresent(variant -> assignment(output, "variant", variant.key()));
        assignment(output, "selected", conflict.selectedVersion());
        output.append("requested = ");
        stringArray(output, sortedVersions(conflict.requestedVersions()));
        output.append('\n');
        assignment(output, "reason", reason(conflict.reason()));
        output.append('\n');
    }

    private static void assignment(StringBuilder output, String key, String value) {
        output.append(key).append(" = ").append(quote(value)).append('\n');
    }

    private static void stringArray(StringBuilder output, List<String> values) {
        output.append('[');
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                output.append(", ");
            }
            output.append(quote(values.get(index)));
        }
        output.append(']');
    }

    private static List<LockPackage> sortedPackages(List<LockPackage> packages) {
        return packages.stream()
                .sorted(Comparator
                        .comparing((LockPackage lockPackage) ->
                                lockPackage.packageId() + ":" + lockPackage.version() + ":" + lockPackage.scope().lockfileName())
                        .thenComparing(lockPackage -> LockArtifactVariant.of(lockPackage).key()))
                .toList();
    }

    private static List<LockConflict> sortedConflicts(List<LockConflict> conflicts) {
        return conflicts.stream()
                .sorted(Comparator
                        .comparing((LockConflict conflict) -> conflict.packageId().toString())
                        .thenComparing(conflict -> conflict.toolGroup().orElse(""))
                        .thenComparing(conflict -> conflict.variant().map(LockArtifactVariant::key).orElse("")))
                .toList();
    }

    private static List<LockMemberGraph> sortedMemberGraphs(
            List<LockMemberGraph> memberGraphs) {
        return memberGraphs.stream()
                .sorted(Comparator.comparing(memberGraph ->
                        memberGraph.member()
                                + ":"
                                + memberGraph.packageId()
                                + ":"
                                + memberGraph.version()
                                + ":"
                                + memberGraph.variant().key()
                                + ":"
                                + memberGraph.scope().lockfileName()))
                .toList();
    }

    private static List<LockPolicyEffect> sortedPolicyEffects(List<LockPolicyEffect> policyEffects) {
        return policyEffects.stream()
                .sorted(Comparator.comparing(policyEffect -> policyEffect.kind()
                        + ":"
                        + policyEffect.packageId()
                        + ":"
                        + policyEffect.requestedVersion().orElse("")
                        + ":"
                        + policyEffect.source().orElse("")
                        + ":"
                        + policyEffect.policy()))
                .toList();
    }

    private static List<String> sortedStrings(List<String> values) {
        return values.stream().sorted().toList();
    }

    private List<String> sortedVersions(List<String> values) {
        return values.stream()
                .sorted(versionComparator.thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static String reason(ConflictSelectionReason reason) {
        return switch (reason) {
            case DIRECT_DEPENDENCY -> "direct dependency wins";
            case NEWEST_VERSION -> "newest version wins";
        };
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\b' -> quoted.append("\\b");
                case '\t' -> quoted.append("\\t");
                case '\n' -> quoted.append("\\n");
                case '\f' -> quoted.append("\\f");
                case '\r' -> quoted.append("\\r");
                default -> {
                    if (character < 0x20 || character == 0x7F) {
                        quoted.append("\\u%04X".formatted((int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
