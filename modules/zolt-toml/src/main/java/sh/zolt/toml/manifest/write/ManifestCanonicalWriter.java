package sh.zolt.toml.manifest.write;

import java.util.Objects;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredManifest;

/** Projects one authored manifest into the frozen canonical TOML order. */
final class ManifestCanonicalWriter {
    private final ManifestIdentityWriter identity = new ManifestIdentityWriter();
    private final ManifestToolchainWriter toolchains = new ManifestToolchainWriter();
    private final ManifestSharedWriter shared = new ManifestSharedWriter();
    private final ManifestDependencyWriter dependencies = new ManifestDependencyWriter();
    private final ManifestBuildCompilerWriter buildCompiler = new ManifestBuildCompilerWriter();
    private final ManifestResourcesWriter resources = new ManifestResourcesWriter();
    private final ManifestGeneratedSourcesWriter generated = new ManifestGeneratedSourcesWriter();
    private final ManifestTestsCoverageWriter testsCoverage = new ManifestTestsCoverageWriter();
    private final ManifestPackagingWriter packaging = new ManifestPackagingWriter();
    private final ManifestPublishingWriter publishing = new ManifestPublishingWriter();
    private final ManifestCommandsWriter commands = new ManifestCommandsWriter();

    String write(AuthoredManifest manifest) {
        AuthoredManifest authored = Objects.requireNonNull(
                manifest, "Authored manifest is required.");
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        identity.write(emitter, authored.workspace(), authored.project());
        toolchains.write(emitter, authored.toolchains());
        shared.write(
                emitter,
                authored.versions(),
                authored.repositories(),
                authored.credentials(),
                authored.platforms());
        dependencies.write(
                emitter,
                authored.dependencies(),
                authored.dependencyConstraints(),
                authored.dependencyPolicy());

        AuthoredBuildConfiguration build = authored.build();
        buildCompiler.write(emitter, build.build(), build.compiler());
        resources.write(emitter, build.resources());
        ManifestRelativePath outputRoot = build.build()
                .flatMap(value -> value.output())
                .flatMap(value -> value.root())
                .orElseGet(() -> new ManifestRelativePath("target"));
        generated.write(emitter, authored.generated(), outputRoot);
        testsCoverage.write(emitter, build.tests(), build.coverage());
        packaging.write(
                emitter,
                authored.packaging(),
                authored.project().map(value -> value.identity().name()));
        authored.publishing().ifPresent(value -> publishing.write(emitter, value));
        authored.commands().ifPresent(value -> commands.write(emitter, value));
        return emitter.finish();
    }
}
