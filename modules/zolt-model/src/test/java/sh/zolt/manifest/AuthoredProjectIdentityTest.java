package sh.zolt.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.project.toolchain.JavaFeatureRelease;

final class AuthoredProjectIdentityTest {
    @Test
    void representsACompactWorkspaceMemberWithoutMaterializingDefaults() {
        AuthoredProjectIdentity project = new AuthoredProjectIdentity(
                new ProjectName("orders-core"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        assertEquals("orders-core", project.name().value());
        assertEquals(Optional.empty(), project.version());
        assertEquals(Optional.empty(), project.group());
        assertEquals(Optional.empty(), project.javaRelease());
    }

    @Test
    void representsACompleteStandaloneProjectIdentity() {
        AuthoredProjectIdentity project = new AuthoredProjectIdentity(
                new ProjectName("hello"),
                Optional.of(new ProjectVersion("0.1.0-SNAPSHOT")),
                Optional.of(new ProjectGroup("com.example")),
                Optional.of(new JavaFeatureRelease(21)),
                Optional.of(new ProjectLicense.Identifier("MIT")));

        assertEquals("0.1.0-SNAPSHOT", project.version().orElseThrow().value());
        assertEquals("com.example", project.group().orElseThrow().value());
        assertEquals("MIT", ((ProjectLicense.Identifier) project.license().orElseThrow()).id());
    }

    @Test
    void retainsCustomOrCompoundLicenseMetadataAsASeparateAuthoredForm() {
        ProjectLicense.Metadata license = new ProjectLicense.Metadata(
                Optional.of("Apache-2.0 OR MIT"),
                Optional.of("Apache License 2.0 or MIT License"),
                Optional.of("https://example.com/licenses"));

        assertEquals("Apache-2.0 OR MIT", license.id().orElseThrow());
        assertEquals("https://example.com/licenses", license.url().orElseThrow());
    }

    @Test
    void rejectsValuesOutsideTheFinalIdentityGrammar() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectName("orders:core"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectGroup("com/example"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectVersion("[1.0,2.0)"));
        assertThrows(IllegalArgumentException.class, () -> new ProjectLicense.Identifier("Apache-2.0 OR MIT"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProjectLicense.Metadata(Optional.empty(), Optional.empty(), Optional.empty()));
    }
}
