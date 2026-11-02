package sh.zolt.lockfile;

import sh.zolt.dependency.PackageId;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import java.util.List;
import java.util.Optional;

/**
 * A recorded version mediation. {@code toolGroup} is the wire field {@code tool}: it names the
 * isolated resolution whose own closure mediated this conflict, and is empty for the main project
 * graph. Exec tools retain their authored local ID; compiler closures use the reserved stable key
 * {@code compiler:<scope>:<closure-root-identity>}. The attribution keeps the audit trail
 * unambiguous when the same GA mediates in more than one place, including compiler closures from
 * workspace members configured with different compiler versions.
 *
 * <p>{@code variant} qualifies the mediation to a single artifact variant when the workspace layer
 * mediates within a variant lane rather than across a whole {@code groupId:artifactId}: two variants of
 * one GA (a plain jar and a classified jar) are distinct artifacts that mediate independently, so a
 * conflict among one variant's versions carries that variant. It is additive and follows the
 * {@code toolGroup} precedent — empty for the default variant, so a lock whose conflicts are all plain
 * jars stays byte-identical.
 */
public record LockConflict(
        PackageId packageId,
        String selectedVersion,
        List<String> requestedVersions,
        ConflictSelectionReason reason,
        Optional<String> toolGroup,
        Optional<LockArtifactVariant> variant,
        List<String> members) {
    private static final String COMPILER_TOOL_GROUP_PREFIX = "compiler:";

    public LockConflict {
        requestedVersions = List.copyOf(requestedVersions);
        toolGroup = toolGroup == null ? Optional.empty() : toolGroup;
        // The default variant carries no discriminator, so a caller passing an explicit default jar and a
        // caller passing nothing collapse to the same empty — keeping conflict dedup, sort, and codec
        // output byte-identical for variant-free locks.
        variant = variant == null ? Optional.empty() : variant.filter(value -> !value.isDefault());
        members = members == null ? List.of() : List.copyOf(members);
    }

    /** Stable {@code tool} attribution for one isolated compiler closure. */
    public static String compilerToolGroup(DependencyScope scope, String closureRootIdentity) {
        String identity = closureRootIdentity == null ? "" : closureRootIdentity.strip();
        if (identity.isEmpty()) {
            throw new IllegalArgumentException("Compiler conflict resolution group requires a closure root identity.");
        }
        return compilerToolGroupPrefix(scope) + identity;
    }

    /** Prefix shared by compiler closures in one scope, for display and filtering consumers. */
    public static String compilerToolGroupPrefix(DependencyScope scope) {
        return switch (scope) {
            case TOOL_GROOVY, TOOL_KOTLIN -> COMPILER_TOOL_GROUP_PREFIX + scope.lockfileName() + ":";
            default -> throw new IllegalArgumentException(
                    "Compiler conflict resolution group requires a compiler-tool scope.");
        };
    }

    /** Whether an isolated-resolution key belongs to one compiler scope. */
    public static boolean isCompilerToolGroup(String value, DependencyScope scope) {
        String prefix = compilerToolGroupPrefix(scope);
        return value != null && value.length() > prefix.length() && value.startsWith(prefix);
    }

    /** Whether an isolated-resolution key belongs to the compiler-reserved namespace. */
    public static boolean isReservedCompilerToolGroup(String value) {
        return value != null && value.startsWith(COMPILER_TOOL_GROUP_PREFIX);
    }

    public LockConflict(
            PackageId packageId,
            String selectedVersion,
            List<String> requestedVersions,
            ConflictSelectionReason reason,
            Optional<String> toolGroup,
            Optional<LockArtifactVariant> variant) {
        this(
                packageId,
                selectedVersion,
                requestedVersions,
                reason,
                toolGroup,
                variant,
                List.of());
    }

    public LockConflict(
            PackageId packageId,
            String selectedVersion,
            List<String> requestedVersions,
            ConflictSelectionReason reason,
            Optional<String> toolGroup) {
        this(
                packageId,
                selectedVersion,
                requestedVersions,
                reason,
                toolGroup,
                Optional.empty(),
                List.of());
    }

    public LockConflict(
            PackageId packageId,
            String selectedVersion,
            List<String> requestedVersions,
            ConflictSelectionReason reason) {
        this(
                packageId,
                selectedVersion,
                requestedVersions,
                reason,
                Optional.empty(),
                Optional.empty(),
                List.of());
    }
}
