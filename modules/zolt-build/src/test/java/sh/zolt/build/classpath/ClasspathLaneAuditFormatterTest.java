package sh.zolt.build.classpath;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.classpath.Classpath;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import org.junit.jupiter.api.Test;

final class ClasspathLaneAuditFormatterTest {
    private final ClasspathLaneAuditFormatter formatter = new ClasspathLaneAuditFormatter();

    @Test
    void formatsTextLanePolicyAndResolvedPackages() {
        String output = formatter.formatText(lockfile());

        assertTrue(output.contains("Classpath lane audit\n\nLane policy:\n"));
        assertTrue(output.contains("compile"));
        assertTrue(output.contains("package-default"));
        assertTrue(output.contains("provided-container"));
        assertTrue(output.contains("development-only"));
        assertTrue(output.contains("processor-only"));
        assertTrue(output.contains("tool-spring-aot"));
        assertTrue(output.contains("spring-aot-tooling-only"));
        assertTrue(output.contains("tool-openapi"));
        assertTrue(output.contains("openapi-generator-tooling-only"));
        assertTrue(output.contains("tool-protobuf"));
        assertTrue(output.contains("protobuf-generator-tooling-only"));
        assertTrue(output.contains("tool-coverage"));
        assertTrue(output.contains("coverage-tooling-only"));
        assertTrue(output.contains("- com.example:compile-lib:1.0.0 [compile] lanes=compile,runtime,test package=package-default"));
        assertTrue(output.contains("- com.example:devtools:1.0.0 [dev] lanes=runtime,test package=development-only"));
        assertTrue(output.contains("- jakarta.servlet:jakarta.servlet-api:6.1.0 [provided] lanes=compile package=provided-container"));
    }

    private static ZoltLockfile lockfile() {
        return new ZoltLockfileReader().read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:compile-lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "."
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "."
                id = "com.example:devtools"
                version = "1.0.0"
                lane = "dev"
                resolvedScope = "dev"

                [[package]]
                id = "jakarta.servlet:jakarta.servlet-api"
                version = "6.1.0"
                source = "maven-central"
                scope = "provided"
                direct = true
                jar = "jakarta/servlet/jakarta.servlet-api/6.1.0/jakarta.servlet-api-6.1.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:devtools"
                version = "1.0.0"
                source = "maven-central"
                scope = "dev"
                direct = true
                jar = "com/example/devtools/1.0.0/devtools-1.0.0.jar"
                dependencies = []

                [[package]]
                id = "com.example:compile-lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "com/example/compile-lib/1.0.0/compile-lib-1.0.0.jar"
                dependencies = []
                """);
    }

}
