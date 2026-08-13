package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.LockPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

final class LockfilePackageCodecTest {
    private final LockfilePackageCodec codec = new LockfilePackageCodec();

    @Test
    void readsPackageEntriesWithoutFileIo() {
        LockPackage lockPackage = codec.packages(Toml.parse("""
                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/demo/1.0.0/demo-1.0.0.jar"
                jarSha256 = "jar-checksum"
                members = ["apps/api"]
                exportedBy = ["modules/api"]
                policies = ["dependency-policy"]
                dependencies = ["com.example:dep:1.0.0"]
                """).getArray("package")).getFirst();

        assertEquals(new PackageId("com.example", "demo"), lockPackage.packageId());
        assertEquals("1.0.0", lockPackage.version());
        assertEquals(DependencyScope.COMPILE, lockPackage.scope());
        assertEquals("com/example/demo/1.0.0/demo-1.0.0.jar", lockPackage.jar().orElseThrow());
        assertEquals(List.of("apps/api"), lockPackage.members());
        assertEquals(List.of("modules/api"), lockPackage.exportedBy());
        assertEquals(List.of("dependency-policy"), lockPackage.policies());
        assertEquals(List.of("com.example:dep:1.0.0"), lockPackage.dependencies());
    }

    @Test
    void rejectsInvalidPackageEntryShape() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> codec.packages(Toml.parse("""
                        package = ["not-a-table"]
                        """).getArray("package")));

        assertEquals("Invalid package entry at index 0 in zolt.lock.", exception.getMessage());
    }

    @Test
    void rejectsInvalidPackageScopeWithPackageId() {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> codec.packages(Toml.parse("""
                        [[package]]
                        id = "com.example:demo"
                        version = "1.0.0"
                        source = "maven-central"
                        scope = "unknown"
                        direct = true
                        dependencies = []
                        """).getArray("package")));

        assertEquals("Invalid scope `unknown` for com.example:demo in zolt.lock.", exception.getMessage());
    }

    @Test
    void rejectsUnsafeJarPathAtParseTime() {
        assertUnsafePath("jar", "../../outside.jar");
    }

    @Test
    void rejectsUnsafePomPathAtParseTime() {
        assertUnsafePath("pom", "/tmp/outside.pom");
    }

    @Test
    void rejectsUnsafeClassifierPathAtParseTime() {
        assertUnsafePath("artifact", "C:/cache/demo-linux.jar");
    }

    @Test
    void rejectsUnsafeSecondaryArtifactPathAtParseTime() {
        assertUnsafePath("artifact", "\\\\server\\share\\platform.properties");
    }

    @Test
    void rejectsBackslashesOnEveryPlatform() {
        assertUnsafePath("jar", "com\\example\\demo.jar");
    }

    private void assertUnsafePath(String field, String value) {
        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> codec.packages(Toml.parse("""
                        [[package]]
                        id = "com.example:demo"
                        version = "1.0.0"
                        source = "central"
                        scope = "compile"
                        direct = true
                        %s = %s
                        dependencies = []
                        """.formatted(field, tomlLiteral(value))).getArray("package")));

        assertTrue(exception.getMessage().contains("com.example:demo"));
        assertTrue(exception.getMessage().contains(field));
    }

    private static String tomlLiteral(String value) {
        return "'" + value + "'";
    }
}
