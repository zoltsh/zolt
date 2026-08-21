package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BomSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.toml.ZoltTomlParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Byte-equality golden POM tests: the emitted POM must equal a checked-in fixture exactly, so any
 * change to element order, indentation, or the dependencyManagement/classifier shape is caught.
 */
final class PublishPomGoldenTest {
    private final PublishPomGenerator generator = new PublishPomGenerator();

    @Test
    void classifierAndTypeRideJarPomInMavenElementOrder() throws IOException {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "app"
                version = "1.0.0"
                group = "com.example"
                java = "21"

                [api.dependencies]
                "io.netty:netty-transport-native-epoll" = { version = "4.1.100.Final", classifier = "linux-x86_64" }
                "org.example:widget" = { version = "2.0.0", type = "zip" }
                """);
        LockArtifactVariant netty = new LockArtifactVariant("jar", Optional.of("linux-x86_64"));
        LockArtifactVariant widget = new LockArtifactVariant("zip", Optional.empty());
        ZoltLockfile lockfile = lockfile(
                List.of(
                        external("io.netty", "netty-transport-native-epoll", "4.1.100.Final",
                                DependencyScope.COMPILE, netty),
                        external("org.example", "widget", "2.0.0", DependencyScope.COMPILE, widget)),
                List.of(
                        root("io.netty", "netty-transport-native-epoll", "4.1.100.Final", netty),
                        root("org.example", "widget", "2.0.0", widget)));

        assertEquals(golden("classifier.pom.xml"), generator.generate(config, lockfile));
    }

    @Test
    void interMemberWorkspaceDependencyRendersRealGavAlongsideExternal() throws IOException {
        ProjectConfig config = new ZoltTomlParser().parse("""
                [project]
                name = "acme-http"
                version = "1.0.0"
                group = "com.acme"
                java = "21"

                [api.dependencies]
                "com.acme:acme-core" = { workspace = "acme-core" }
                "org.slf4j:slf4j-api" = "2.0.13"
                """);
        ZoltLockfile lockfile = lockfile(
                List.of(
                        workspacePackage("com.acme", "acme-core", "1.0.0", "acme-core"),
                        external("org.slf4j", "slf4j-api", "2.0.13", DependencyScope.COMPILE)),
                List.of(
                        root("com.acme", "acme-core", "1.0.0", LockArtifactVariant.defaultVariant()),
                        root("org.slf4j", "slf4j-api", "2.0.13", LockArtifactVariant.defaultVariant())));

        assertEquals(golden("inter-member.pom.xml"), generator.generate(config, lockfile));
    }

    @Test
    void bomFamilyEmitsSortedDependencyManagementFromMembersPinsAndImports() throws IOException {
        PublicationMetadata metadata = new PublicationMetadata(
                "Acme Platform BOM",
                "Curated platform versions for the Acme family.",
                "",
                "",
                List.of(),
                "",
                "");
        BomSettings bom = new BomSettings(
                BomSettings.Members.none(),
                List.of(
                        new BomSettings.ManagedVersion("org.postgresql:postgresql", "42.7.4", null, null, null),
                        new BomSettings.ManagedVersion(
                                "io.netty:netty-transport-native-epoll", "4.1.100.Final", "netty", "linux-x86_64", null)),
                List.of(new BomSettings.ImportedBom("com.fasterxml.jackson:jackson-bom", "2.17.0", null)));
        ProjectConfig config = base("acme-bom", "1.0.0", "com.acme.platform", metadata)
                .withPackageSettings(new PackageSettings(PackageMode.BOM, false, false, false, metadata).withBom(bom));
        ZoltLockfile lockfile = new ZoltLockfile(
                7,
                List.of(
                        workspacePackage("com.acme", "acme-core", "1.0.0", "acme-core"),
                        workspacePackage("com.acme", "acme-http", "1.0.0", "acme-http")),
                List.of());

        assertEquals(golden("bom-family.pom.xml"), generator.generate(config, lockfile));
    }

    private static ProjectConfig base(String name, String version, String group, PublicationMetadata metadata) {
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(name, version, group, "21", Optional.empty()),
                        Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withPackageSettings(new PackageSettings(PackageMode.THIN, false, false, false, metadata));
    }

    private static LockPackage external(String group, String artifact, String version, DependencyScope scope) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                ProjectConfig.MAVEN_CENTRAL,
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    private static LockPackage external(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            LockArtifactVariant variant) {
        String classifier = variant.classifier().map(value -> "-" + value).orElse("");
        String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + classifier + "." + variant.extension();
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                ProjectConfig.MAVEN_CENTRAL,
                scope,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(path),
                Optional.of(variant.extension()),
                Optional.empty(),
                List.of());
    }

    private static LockPackage workspacePackage(String group, String artifact, String version, String memberPath) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(memberPath),
                Optional.of("target/classes"),
                List.of());
    }

    private static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                7, Optional.empty(), Optional.empty(), List.of(), packages, List.of(), List.of(), List.of(), roots);
    }

    private static LockDependencyRoot root(
            String group,
            String artifact,
            String version,
            LockArtifactVariant variant) {
        return new LockDependencyRoot(
                ".",
                new PackageId(group, artifact),
                version,
                variant,
                DependencyLane.API,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);
    }

    private static String golden(String name) throws IOException {
        return new String(
                PublishPomGoldenTest.class.getResourceAsStream("/golden/" + name).readAllBytes(),
                StandardCharsets.UTF_8);
    }
}
