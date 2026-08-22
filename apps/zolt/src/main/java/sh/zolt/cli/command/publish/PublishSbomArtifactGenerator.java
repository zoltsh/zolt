package sh.zolt.cli.command.publish;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.sbom.CycloneDxSbomWriter;
import sh.zolt.sbom.LicenseIndex;
import sh.zolt.sbom.LockSbomAssembler;
import sh.zolt.sbom.SbomModel;
import sh.zolt.sbom.SbomScopeSelection;
import sh.zolt.workspace.discovery.ManifestProject;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.publish.WorkspaceMemberSbomGenerator;

/**
 * Generates the lock-only CycloneDX SBOM attached by {@code zolt publish --sbom} and writes it next
 * to the generated POM as {@code <name>-<version>-cyclonedx.json}. Dependency license resolution is
 * available via {@code zolt licenses}/{@code zolt sbom}; the published artifact stays cache-free and
 * byte-reproducible (components, hashes, edges, and the config-authoritative root license).
 */
final class PublishSbomArtifactGenerator {
    private final ManifestProjectLoader projectLoader = new ManifestProjectLoader();
    private final ZoltLockfileReader lockfileReader = new ZoltLockfileReader();
    private final LockSbomAssembler assembler = new LockSbomAssembler();
    private final CycloneDxSbomWriter writer = new CycloneDxSbomWriter();

    Optional<Path> generate(boolean enabled, Path projectRoot, String toolVersion) {
        if (!enabled) {
            return Optional.empty();
        }
        ManifestProject project = projectLoader.project(projectRoot);
        ZoltLockfile lockfile = lockfileReader.read(
                sh.zolt.cli.command.CommandProjectLockfile.path(project));
        return Optional.of(write(projectRoot, project.config(), lockfile, toolVersion));
    }

    /**
     * Builds the per-member SBOM generator for {@code zolt publish --workspace}: when enabled, each
     * non-BOM member's SBOM is written from its policy-merged config and projected publish lock (a
     * workspace member has no {@code zolt.lock} of its own), beside the member POM exactly as the
     * single-project artifact. When disabled, no SBOM is attached.
     */
    WorkspaceMemberSbomGenerator memberGenerator(boolean enabled, String toolVersion) {
        if (!enabled) {
            return WorkspaceMemberSbomGenerator.disabled();
        }
        return (memberDirectory, memberConfig, memberLock) ->
                Optional.of(write(memberDirectory, memberConfig, memberLock, toolVersion));
    }

    private Path write(Path root, ProjectConfig config, ZoltLockfile lockfile, String toolVersion) {
        SbomModel model = assembler.assemble(
                config, lockfile, SbomScopeSelection.requiredOnly(), Optional.empty(),
                toolVersion, LicenseIndex.empty());
        Path sbomPath = root.resolve(config.build().outputRoot()).resolve("publish")
                .resolve(config.project().name() + "-" + config.project().version() + "-cyclonedx.json")
                .normalize();
        try {
            Files.createDirectories(sbomPath.getParent());
            Files.writeString(sbomPath, writer.write(model), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return sbomPath;
    }
}
