package sh.zolt.update;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.manifest.authored.AuthoredDependencies;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.mutation.AuthoredManifestMutator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies policy and exact update plans to one authored manifest.
 *
 * <p>Every write goes through {@link AuthoredManifestMutator}, so the caller can hand the result to
 * the source-safe editor and get a byte-preserving patch of exactly the declarations named by the
 * plan. Metadata that is not a version — {@code optional}, {@code publishOnly}, {@code classifier},
 * {@code type}, {@code exclude}, a constraint {@code reason} — is carried across unchanged, and a
 * declaration that no longer holds the planned literal is never overwritten.
 */
public final class UpdateApplier {

    public AuthoredManifest apply(AuthoredManifest manifest, UpdatePlan plan) {
        Objects.requireNonNull(plan, "plan");
        return apply(manifest, plan.edits());
    }

    public AuthoredManifest apply(AuthoredManifest manifest, ExactUpdatePlan plan) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(plan, "plan");
        if (!plan.changed()) {
            return manifest;
        }
        UpdateTarget target = plan.target();
        return apply(manifest, List.of(new UpdateEdit(
                target.surface(),
                target.identifier(),
                target.section(),
                plan.fromVersion(),
                plan.toVersion(),
                plan.changeClass().orElseThrow(),
                target.governs())));
    }

    private AuthoredManifest apply(AuthoredManifest manifest, List<UpdateEdit> edits) {
        AuthoredManifest updated = Objects.requireNonNull(manifest, "manifest");
        for (UpdateEdit edit : edits) {
            updated = switch (edit.surface()) {
                case VERSION_ALIAS -> AuthoredManifestMutator.setVersionAlias(
                        updated, new LocalId(edit.identifier()), new VersionAliasValue(edit.toVersion()));
                case DEPENDENCY, ANNOTATION_PROCESSOR -> applyDependency(updated, edit);
                case PLATFORM -> AuthoredManifestMutator.setPlatform(
                        updated,
                        new DependencyCoordinate(edit.identifier()),
                        new PlatformSelector.FixedVersion(edit.toVersion()));
                case DEPENDENCY_CONSTRAINT -> applyConstraint(updated, edit);
                case BOM_VERSION -> applyBomVersion(updated, edit);
                case BOM_IMPORT -> AuthoredManifestMutator.setBomImport(
                        updated,
                        new DependencyCoordinate(edit.identifier()),
                        new PlatformSelector.FixedVersion(edit.toVersion()));
                case EXEC_TOOL_COORDINATE, PROTOBUF_TOOL, OPENAPI_TOOL -> throw new IllegalArgumentException(
                        "Update surface `" + edit.surface().jsonName() + "` is not mutable.");
            };
        }
        return updated;
    }

    private static AuthoredManifest applyDependency(AuthoredManifest manifest, UpdateEdit edit) {
        DependencyLane lane = ManifestSections.laneOf(edit.section());
        DependencyCoordinate coordinate = new DependencyCoordinate(edit.identifier());
        AuthoredDependency existing = manifest.dependencies()
                .map(AuthoredDependencies::declarations)
                .orElseGet(List::of)
                .stream()
                .filter(candidate -> candidate.lane() == lane && candidate.coordinate().equals(coordinate))
                .findFirst()
                .orElse(null);
        if (existing == null || !(existing.selector() instanceof DependencySelector.FixedVersion)) {
            return manifest;
        }
        return AuthoredManifestMutator.setDependency(
                manifest,
                new AuthoredDependency(
                        lane,
                        coordinate,
                        new DependencySelector.FixedVersion(edit.toVersion()),
                        existing.metadata()));
    }

    private static AuthoredManifest applyConstraint(AuthoredManifest manifest, UpdateEdit edit) {
        DependencyCoordinate coordinate = new DependencyCoordinate(edit.identifier());
        AuthoredDependencyConstraint existing = manifest.dependencyConstraints()
                .map(AuthoredDependencyConstraints::entries)
                .orElseGet(Map::of)
                .get(coordinate);
        if (existing == null || !(existing.selector() instanceof DependencyConstraintSelector.FixedVersion)) {
            return manifest;
        }
        return AuthoredManifestMutator.setDependencyConstraint(
                manifest,
                coordinate,
                new AuthoredDependencyConstraint(
                        new DependencyConstraintSelector.FixedVersion(edit.toVersion()), existing.reason()));
    }

    private static AuthoredManifest applyBomVersion(AuthoredManifest manifest, UpdateEdit edit) {
        DependencyCoordinate coordinate = new DependencyCoordinate(edit.identifier());
        AuthoredBom.Version existing = manifest.packaging().bom()
                .flatMap(AuthoredBom::versions)
                .orElseGet(Map::of)
                .get(coordinate);
        if (existing == null || !(existing.selector() instanceof PlatformSelector.FixedVersion)) {
            return manifest;
        }
        return AuthoredManifestMutator.setBomVersion(
                manifest,
                coordinate,
                new AuthoredBom.Version(
                        new PlatformSelector.FixedVersion(edit.toVersion()),
                        existing.classifier(),
                        existing.type()));
    }
}
