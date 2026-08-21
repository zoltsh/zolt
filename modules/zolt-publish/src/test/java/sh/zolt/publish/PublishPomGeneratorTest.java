package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.NativeSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PublishPomGeneratorTest {
    private final PublishPomGenerator generator = new PublishPomGenerator();

    @Test
    void emitsCoordinatesMetadataAndSortedScopedDependencies() {
        PublicationMetadata metadata = new PublicationMetadata(
                "App Library",
                "",
                "https://example.test/app",
                "Apache-2.0",
                List.of("Ada Lovelace"),
                "https://github.com/example/app",
                "https://github.com/example/app/issues");
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "app"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.13"

                [dependencies.runtime]
                "com.zaxxer:HikariCP" = "5.1.0"

                [dependencies.provided]
                "jakarta.servlet:jakarta.servlet-api" = "6.0.0"
                """).withPackageSettings(new PackageSettings(PackageMode.THIN, false, false, false, metadata));

        // Deliberately out of coordinate order to prove the generator sorts.
        ZoltLockfile lockfile = lockfile(
                List.of(
                        direct("org.slf4j", "slf4j-api", "2.0.13", DependencyScope.COMPILE),
                        direct("com.zaxxer", "HikariCP", "5.1.0", DependencyScope.RUNTIME),
                        direct("jakarta.servlet", "jakarta.servlet-api", "6.0.0", DependencyScope.PROVIDED)),
                List.of(
                        root("org.slf4j", "slf4j-api", "2.0.13", DependencyLane.API, DependencyScope.COMPILE),
                        root("com.zaxxer", "HikariCP", "5.1.0", DependencyLane.RUNTIME, DependencyScope.RUNTIME),
                        root("jakarta.servlet", "jakarta.servlet-api", "6.0.0", DependencyLane.PROVIDED, DependencyScope.PROVIDED)));

        String pom = generator.generate(config, lockfile);

        assertTrue(pom.contains("<modelVersion>4.0.0</modelVersion>"));
        assertTrue(pom.contains("<groupId>com.example</groupId>"));
        assertTrue(pom.contains("<artifactId>app</artifactId>"));
        assertTrue(pom.contains("<version>1.0.0</version>"));
        assertTrue(pom.contains("<name>App Library</name>"));
        assertTrue(pom.contains("<url>https://example.test/app</url>"));
        assertTrue(pom.contains("<name>Apache-2.0</name>"));
        assertTrue(pom.contains("<name>Ada Lovelace</name>"));
        // Blank description is omitted entirely.
        assertTrue(!pom.contains("<description>"));
        // Default jar packaging is left implicit.
        assertTrue(!pom.contains("<packaging>"));

        // Compile scope is implicit (no <scope> element); runtime/provided are emitted.
        // Indentation is load-bearing, so these fragments keep the literal POM spacing.
        assertTrue(pom.contains("    <dependency>\n"
                + "      <groupId>org.slf4j</groupId>\n"
                + "      <artifactId>slf4j-api</artifactId>\n"
                + "      <version>2.0.13</version>\n"
                + "    </dependency>\n"));
        assertTrue(pom.contains("    <dependency>\n"
                + "      <groupId>com.zaxxer</groupId>\n"
                + "      <artifactId>HikariCP</artifactId>\n"
                + "      <version>5.1.0</version>\n"
                + "      <scope>runtime</scope>\n"
                + "    </dependency>\n"));
        assertTrue(pom.contains("<scope>provided</scope>"));

        // Sorted by coordinate: com.zaxxer < jakarta.servlet < org.slf4j.
        int hikari = pom.indexOf("HikariCP");
        int servlet = pom.indexOf("jakarta.servlet-api");
        int slf4j = pom.indexOf("slf4j-api");
        assertTrue(hikari < servlet && servlet < slf4j);
    }

    @Test
    void emitsPackagingLicenseUrlStructuredDevelopersAndScmDetails() {
        PublicationMetadata metadata = new PublicationMetadata(
                "App Library",
                "A test library.",
                "https://example.test/app",
                "Apache-2.0",
                "https://www.apache.org/licenses/LICENSE-2.0.txt",
                List.of(),
                List.of(new DeveloperEntry(
                        "ada",
                        "Ada Lovelace",
                        "ada@example.test",
                        "Analytical Engines",
                        "https://example.test/ada")),
                "https://github.com/example/app",
                "scm:git:https://github.com/example/app.git",
                "scm:git:ssh://git@github.com/example/app.git",
                "v1.0.0",
                "https://github.com/example/app/issues");
        ProjectConfig config = config(PackageMode.WAR, metadata, Map.of());

        String pom = generator.generate(config, new ZoltLockfile(7, List.of(), List.of()));

        // WAR packaging is explicit; jar stays implicit.
        assertTrue(pom.contains("<packaging>war</packaging>"));
        // License carries name and url.
        assertTrue(pom.contains("<name>Apache-2.0</name>"));
        assertTrue(pom.contains("<url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>"));
        // Structured developer emits id/name/email/organization/url.
        assertTrue(pom.contains("      <id>ada</id>\n"
                + "      <name>Ada Lovelace</name>\n"
                + "      <email>ada@example.test</email>\n"
                + "      <organization>Analytical Engines</organization>\n"
                + "      <url>https://example.test/ada</url>\n"));
        // SCM carries connection, developerConnection, tag and url in Maven order.
        assertTrue(pom.contains("  <scm>\n"
                + "    <connection>scm:git:https://github.com/example/app.git</connection>\n"
                + "    <developerConnection>scm:git:ssh://git@github.com/example/app.git</developerConnection>\n"
                + "    <tag>v1.0.0</tag>\n"
                + "    <url>https://github.com/example/app</url>\n"
                + "  </scm>\n"));
    }

    @Test
    void escapesXmlSpecialCharactersInMetadata() {
        PublicationMetadata metadata = new PublicationMetadata(
                "A & B <lib> \"quoted\" 'apos'",
                "",
                "",
                "",
                List.of(),
                "",
                "");
        ProjectConfig config = config(metadata, Map.of());

        String pom = generator.generate(config, new ZoltLockfile(7, List.of(), List.of()));

        assertTrue(pom.contains("<name>A &amp; B &lt;lib&gt; &quot;quoted&quot; &apos;apos&apos;</name>"));
        assertTrue(!pom.contains("<name>A & B"));
    }

    @Test
    void dropsUnpublishedLanesAndUsesLockedPublishOnlyFacts() {
        DependencyMetadata slf4jPublishOnly = new DependencyMetadata(
                "dependencies",
                "org.slf4j:slf4j-api",
                "2.0.13",
                false,
                null,
                true,
                true,
                List.of(new DependencyExclusionSpec("commons-logging", "commons-logging")));
        ProjectConfig config = config(
                PublicationMetadata.empty(),
                Map.of(DependencyMetadata.key("dependencies", "org.slf4j:slf4j-api"), slf4jPublishOnly));

        LockPackage junit = direct("org.junit.jupiter", "junit-jupiter", "5.11.4", DependencyScope.TEST);
        ZoltLockfile lockfile = lockfile(
                List.of(junit),
                List.of(
                        publishOnlyRoot(
                                "org.slf4j", "slf4j-api", "2.0.13", DependencyLane.IMPLEMENTATION, true),
                        root("org.junit.jupiter", "junit-jupiter", "5.11.4", DependencyLane.TEST, DependencyScope.TEST)));

        String pom = generator.generate(config, lockfile);

        // junit (test scope) dropped.
        assertTrue(!pom.contains("junit-jupiter"));
        // The root's exact version and authored implementation lane are authoritative.
        assertEquals(1, countOccurrences(pom, "<artifactId>slf4j-api</artifactId>"));
        assertTrue(pom.contains("<version>2.0.13</version>"));
        assertTrue(pom.contains("<scope>runtime</scope>"));
        assertTrue(pom.contains("<optional>true</optional>"));
        assertTrue(pom.contains("      <exclusions>\n"
                + "        <exclusion>\n"
                + "          <groupId>commons-logging</groupId>\n"
                + "          <artifactId>commons-logging</artifactId>\n"
                + "        </exclusion>\n"
                + "      </exclusions>\n"));
    }

    @Test
    void apiDependencyKeepsClassifierTypeOptionalAndExclusions() {
        ProjectConfig config = new ManifestProjectConfigLoader().load("""
                [project]
                name = "app"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [dependencies.api]
                "io.netty:netty-transport-native-epoll" = { version = "4.1.100.Final", classifier = "linux-x86_64", type = "zip", optional = true, exclude = ["io.netty:netty-common"] }
                """);
        LockArtifactVariant variant = new LockArtifactVariant("zip", Optional.of("linux-x86_64"));
        ZoltLockfile lockfile = lockfile(
                List.of(directVariant(
                        "io.netty", "netty-transport-native-epoll", "4.1.100.Final", DependencyScope.COMPILE, variant)),
                List.of(root(
                        "io.netty", "netty-transport-native-epoll", "4.1.100.Final",
                        variant, DependencyLane.API, DependencyScope.COMPILE, true)));

        String pom = generator.generate(config, lockfile);

        assertTrue(pom.contains("<classifier>linux-x86_64</classifier>"));
        assertTrue(pom.contains("<type>zip</type>"));
        assertTrue(pom.contains("<optional>true</optional>"));
        assertTrue(pom.contains("<groupId>io.netty</groupId>\n"
                + "          <artifactId>netty-common</artifactId>"));
    }

    @Test
    void rendersTwoVariantsOfOneGaAsDistinctDependenciesInsteadOfCollapsing() {
        // A member depends on two classified variants of one GA in different published scopes: the plain-GA
        // dedup key would collapse them onto one <dependency>; the variant-aware identity keeps both.
        String coordinate = "io.netty:netty-transport-native-epoll";
        ProjectConfig config = twoVariantConfig(coordinate);

        LockArtifactVariant linuxVariant = new LockArtifactVariant("jar", Optional.of("linux-x86_64"));
        LockArtifactVariant osxVariant = new LockArtifactVariant("jar", Optional.of("osx-aarch_64"));
        ZoltLockfile lockfile = lockfile(
                List.of(
                        directVariant("io.netty", "netty-transport-native-epoll", "4.1.100.Final",
                                DependencyScope.COMPILE, linuxVariant),
                        directVariant("io.netty", "netty-transport-native-epoll", "4.1.100.Final",
                                DependencyScope.PROVIDED, osxVariant)),
                List.of(
                        root("io.netty", "netty-transport-native-epoll", "4.1.100.Final",
                                linuxVariant, DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE),
                        root("io.netty", "netty-transport-native-epoll", "4.1.100.Final",
                                osxVariant, DependencyLane.PROVIDED, DependencyScope.PROVIDED)));

        String pom = generator.generate(config, lockfile);

        assertEquals(2, countOccurrences(pom, "<artifactId>netty-transport-native-epoll</artifactId>"));
        assertTrue(pom.contains("<classifier>linux-x86_64</classifier>"));
        assertTrue(pom.contains("<classifier>osx-aarch_64</classifier>"));
        assertTrue(pom.contains("<scope>provided</scope>"));
    }

    @Test
    void invalidPublishOnlyCoordinateRaisesPublishException() {
        DependencyMetadata invalid = new DependencyMetadata(
                "dependencies",
                "not-a-coordinate",
                "1.0.0",
                false,
                null,
                false,
                true,
                List.of());
        ProjectConfig config = config(
                PublicationMetadata.empty(),
                Map.of(DependencyMetadata.key("dependencies", "not-a-coordinate"), invalid));

        PublishException exception = assertThrows(
                PublishException.class,
                () -> generator.generate(config, new ZoltLockfile(7, List.of(), List.of())));

        assertTrue(exception.getMessage().contains("Invalid dependency coordinate"));
        assertTrue(exception.getMessage().contains("not-a-coordinate"));
    }

    private static ProjectConfig config(
            PublicationMetadata metadata,
            Map<String, DependencyMetadata> dependencyMetadata) {
        return config(PackageMode.THIN, metadata, dependencyMetadata);
    }

    private static ProjectConfig config(
            PackageMode mode,
            PublicationMetadata metadata,
            Map<String, DependencyMetadata> dependencyMetadata) {
        ProjectConfig base = ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("app", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
        return base
                .withPackageSettings(new PackageSettings(mode, false, false, false, metadata))
                .withDependencyMetadata(dependencyMetadata);
    }

    private static ProjectConfig twoVariantConfig(String coordinate) {
        ProjectConfig config = ProjectConfigs.withAllDependencySections(
                new ProjectMetadata("app", "1.0.0", "com.example", "21", Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                Map.of(),
                Map.of(), Set.of(), Map.of(),
                Map.of(coordinate, "4.1.100.Final"), Set.of(), Map.of(),
                Map.of(), Set.of(),
                Map.of(coordinate, "4.1.100.Final"), Set.of(),
                Map.of(), Set.of(),
                Map.of(), Set.of(), Map.of(),
                Map.of(), Set.of(),
                Map.of(), Set.of(),
                BuildSettings.defaults(), NativeSettings.defaults(), CompilerSettings.defaults(), PackageSettings.defaults());
        return config.withDependencyMetadata(Map.of(
                DependencyMetadata.key("dependencies", coordinate),
                new DependencyMetadata(
                        "dependencies", coordinate, null, null, false, null, false, false,
                        List.of(), "linux-x86_64", null),
                DependencyMetadata.key("provided.dependencies", coordinate),
                new DependencyMetadata(
                        "provided.dependencies", coordinate, null, null, false, null, false, false,
                        List.of(), "osx-aarch_64", null)));
    }

    private static LockPackage direct(String group, String artifact, String version, DependencyScope scope) {
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

    private static LockPackage directVariant(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            LockArtifactVariant variant) {
        String suffix = variant.classifier().map(value -> "-" + value).orElse("");
        String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + suffix + "." + variant.extension();
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
            DependencyLane lane,
            DependencyScope scope) {
        return root(group, artifact, version, LockArtifactVariant.defaultVariant(), lane, scope);
    }

    private static LockDependencyRoot root(
            String group,
            String artifact,
            String version,
            LockArtifactVariant variant,
            DependencyLane lane,
            DependencyScope scope) {
        return root(group, artifact, version, variant, lane, scope, false);
    }

    private static LockDependencyRoot root(
            String group,
            String artifact,
            String version,
            LockArtifactVariant variant,
            DependencyLane lane,
            DependencyScope scope,
            boolean optional) {
        return new LockDependencyRoot(
                ".", new PackageId(group, artifact), version, variant, lane, Optional.of(scope), optional, false);
    }

    private static LockDependencyRoot publishOnlyRoot(
            String group,
            String artifact,
            String version,
            DependencyLane lane,
            boolean optional) {
        return new LockDependencyRoot(
                ".", new PackageId(group, artifact), version, null, lane, Optional.empty(), optional, true);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
