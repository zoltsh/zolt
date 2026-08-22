package sh.zolt.build.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import sh.zolt.build.NativeImageException;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import org.junit.jupiter.api.Test;

final class SpringBootNativeBoundaryDiagnosticsTest {
    @Test
    void acceptsSpringBootNativeProjectInsideProvenFixtureFamily() {
        SpringBootNativeBoundaryDiagnostics.rejectUnsupportedEcosystem(project("""
                [dependencies]
                "org.springframework.boot:spring-boot-starter-web" = "3.3.5"
                """));
    }

    @Test
    void rejectsSpringCloudNativeProjectWithActionableBoundaryDiagnostic() {
        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> SpringBootNativeBoundaryDiagnostics.rejectUnsupportedEcosystem(project("""
                        [dependencies]
                        "org.springframework.cloud:spring-cloud-starter-gateway" = "4.1.5"
                        """)));

        assertNotNull(exception.actionableError());
        assertEquals(
                "Spring Cloud native applications are not part of Zolt's proven Spring Boot native fixture family yet. "
                        + "Zolt currently proves Spring Boot 3.3 Java 21 native rows for WebMVC, Actuator, "
                        + "WebMVC contract behavior, and Spring JDBC/H2 data access.",
                exception.actionableError().summary());
        assertEquals(
                "Use the JVM Spring Boot path or add a dedicated Spring Cloud native fixture before enabling native.",
                exception.actionableError().remediation());
    }

    @Test
    void rejectsExternalDatabaseNativeProjectFromRuntimeDependencies() {
        NativeImageException exception = assertThrows(
                NativeImageException.class,
                () -> SpringBootNativeBoundaryDiagnostics.rejectUnsupportedEcosystem(project("""
                        [dependencies.runtime]
                        "org.postgresql:postgresql" = "42.7.3"
                        """)));

        assertEquals(
                "Use the proven Spring JDBC/H2 native fixture row or keep external database projects on the JVM Spring Boot path.",
                exception.actionableError().remediation());
    }

    private static ProjectConfig project(String body) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [framework.spring-boot]
                native = true

                """ + body);
    }
}
