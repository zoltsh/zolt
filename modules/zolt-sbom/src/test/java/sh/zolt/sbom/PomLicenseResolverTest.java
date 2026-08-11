package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockPackage;

final class PomLicenseResolverTest extends SbomTestSupport {

    @Test
    void resolvesDirectlyDeclaredSpdxLicense(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "direct", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>direct</artifactId>
                  <version>1.0.0</version>
                  <licenses>
                    <license>
                      <name>Apache License, Version 2.0</name>
                      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                    </license>
                  </licenses>
                </project>
                """);

        List<SbomLicense> licenses = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "direct", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(1, licenses.size());
        assertEquals(SbomLicenseStatus.SPDX, licenses.getFirst().status());
        assertEquals("Apache-2.0", licenses.getFirst().spdxId().orElseThrow());
    }

    @Test
    void inheritsLicenseFromParentPomThroughTheCache(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "child", "1.0.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>2.0.0</version>
                  </parent>
                  <artifactId>child</artifactId>
                </project>
                """);
        writePom(cache, "org.example", "parent", "2.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>2.0.0</version>
                  <licenses>
                    <license><name>MIT License</name></license>
                  </licenses>
                </project>
                """);

        List<SbomLicense> licenses = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "child", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(List.of("MIT"), labels(licenses));
    }

    @Test
    void inheritsExplicitExpressionFromParentPom(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "child-expression", "1.0.0", """
                <project>
                  <parent>
                    <groupId>org.example</groupId>
                    <artifactId>expression-parent</artifactId>
                    <version>2.0.0</version>
                  </parent>
                  <artifactId>child-expression</artifactId>
                </project>
                """);
        writePom(cache, "org.example", "expression-parent", "2.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>expression-parent</artifactId>
                  <version>2.0.0</version>
                  <licenses><license><name>MIT OR Apache-2.0</name></license></licenses>
                </project>
                """);

        SbomLicense license = new PomLicenseResolver(cache)
                .resolve(maven(
                        "org.example", "child-expression", "1.0.0",
                        DependencyScope.COMPILE, true, SHA_A, List.of()))
                .getFirst();

        assertEquals(SbomLicenseStatus.SPDX_EXPRESSION, license.status());
        assertEquals("MIT OR Apache-2.0", license.label());
    }

    @Test
    void keepsDualLicensesAsDiscreteObjects(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "dual", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>dual</artifactId>
                  <version>1.0.0</version>
                  <licenses>
                    <license><name>Eclipse Public License - v 2.0</name></license>
                    <license><name>GPL2 w/ CPE</name></license>
                  </licenses>
                </project>
                """);

        List<SbomLicense> licenses = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "dual", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(List.of("EPL-2.0", "GPL-2.0-only WITH Classpath-exception-2.0"), labels(licenses));
    }

    @Test
    void preservesAnExplicitSpdxExpressionFromOneLicenseName(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "expression", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>expression</artifactId>
                  <version>1.0.0</version>
                  <licenses>
                    <license><name>MIT and BSD-3-Clause</name><url>https://example.com/license</url></license>
                  </licenses>
                </project>
                """);

        SbomLicense license = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "expression", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()))
                .getFirst();

        assertEquals(SbomLicenseStatus.SPDX_EXPRESSION, license.status());
        assertEquals("MIT AND BSD-3-Clause", license.label());
        assertEquals("MIT and BSD-3-Clause", license.name().orElseThrow());
        assertEquals("https://example.com/license", license.url().orElseThrow());
    }

    @Test
    void keepsMalformedExpressionShapedTextUnmapped(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "malformed", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>malformed</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>MIT AND Not-A-License</name></license></licenses>
                </project>
                """);

        SbomLicense license = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "malformed", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()))
                .getFirst();

        assertEquals(SbomLicenseStatus.UNMAPPED, license.status());
        assertEquals("MIT AND Not-A-License", license.label());
    }

    @Test
    void keepsUnmatchedLicenseRawAsUnmapped(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "weird", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>weird</artifactId>
                  <version>1.0.0</version>
                  <licenses>
                    <license>
                      <name>Weird Proprietary License</name>
                      <url>https://example.com/license</url>
                    </license>
                  </licenses>
                </project>
                """);

        List<SbomLicense> licenses = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "weird", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(1, licenses.size());
        SbomLicense license = licenses.getFirst();
        assertEquals(SbomLicenseStatus.UNMAPPED, license.status());
        assertEquals("Weird Proprietary License", license.name().orElseThrow());
        assertEquals("https://example.com/license", license.url().orElseThrow());
    }

    @Test
    void reportsUnknownWhenNoLicensesInChain(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "bare", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>bare</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        List<SbomLicense> licenses = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "bare", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(1, licenses.size());
        assertEquals(SbomLicenseStatus.UNKNOWN, licenses.getFirst().status());
    }

    @Test
    void reportsUnknownWhenCachedPomIsMissing(@TempDir Path cache) {
        LicenseIndex index = new PomLicenseResolver(cache).index(List.of(
                maven("org.example", "absent", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of())));

        assertEquals(List.of("org.example:absent:1.0.0"), index.unresolved());
        assertEquals(SbomLicenseStatus.UNKNOWN, index.forCoordinate("org.example:absent:1.0.0").getFirst().status());
    }

    @Test
    void guardsAgainstParentCycles(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "a", "1.0.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>b</artifactId><version>1.0.0</version></parent>
                  <artifactId>a</artifactId>
                </project>
                """);
        writePom(cache, "org.example", "b", "1.0.0", """
                <project>
                  <parent><groupId>org.example</groupId><artifactId>a</artifactId><version>1.0.0</version></parent>
                  <artifactId>b</artifactId>
                </project>
                """);

        List<SbomLicense> licenses = new PomLicenseResolver(cache)
                .resolve(maven("org.example", "a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(SbomLicenseStatus.UNKNOWN, licenses.getFirst().status());
    }

    @Test
    void memoizesResolutionPerCoordinate(@TempDir Path cache) throws IOException {
        writePom(cache, "org.example", "memo", "1.0.0", """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>memo</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>ISC</name></license></licenses>
                </project>
                """);
        PomLicenseResolver resolver = new PomLicenseResolver(cache);
        LockPackage lockPackage =
                maven("org.example", "memo", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of());

        assertTrue(resolver.resolve(lockPackage) == resolver.resolve(lockPackage));
    }

    private static List<String> labels(List<SbomLicense> licenses) {
        return licenses.stream().map(SbomLicense::label).sorted().toList();
    }

    private static void writePom(Path cache, String group, String artifact, String version, String xml)
            throws IOException {
        Path pom = cache.resolve(group.replace('.', '/') + "/" + artifact + "/" + version
                + "/" + artifact + "-" + version + ".pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, xml);
    }
}
