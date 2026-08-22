package sh.zolt.build.packageplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.project.PackageMode;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackagePlanFormatterTest {
    private final PackagePlanFormatter formatter = new PackagePlanFormatter();

    @TempDir
    Path tempDir;

    @Test
    void textIncludesRuntimeClasspathDependenciesPoliciesAndWarnings() {
        Path archive = tempDir.resolve("target/demo-0.1.0.jar");
        Path output = tempDir.resolve("target/classes");
        Path runtimeClasspath = tempDir.resolve("target/demo-0.1.0.runtime-classpath");

        PackagePlan plan = new PackagePlan(
                tempDir,
                PackageMode.THIN,
                archive,
                output,
                "archive root",
                Optional.of(runtimeClasspath),
                List.of(
                        new PackagePlanDependency(
                                "com.example:api:1.2.3",
                                "1.2.3",
                                DependencyScope.COMPILE,
                                List.of("main-compile", "main-runtime"),
                                true,
                                "application-runtime",
                                "included",
                                "thin-runtime-lib",
                                "lib/api-1.2.3.jar",
                                "compile dependency is copied beside the thin jar",
                                List.of("strict-version: com.example:api -> 1.2.3")),
                        new PackagePlanDependency(
                                "jakarta.servlet:jakarta.servlet-api:6.1.0",
                                "6.1.0",
                                DependencyScope.PROVIDED,
                                List.of("provided"),
                                false,
                                "provided-container",
                                "omitted",
                                "provided-container-omitted",
                                "",
                                "provided by container",
                                List.of())),
                List.of(new PackagePlanWarning(
                        "CONTAINER_DEPENDENCY_PACKAGED",
                        "jakarta.servlet:jakarta.servlet-api:6.1.0",
                        "war-runtime-lib",
                        "Container dependency is packaged by a WAR rule.",
                        "Move it to [dependencies.provided], then run `zolt resolve`.")));

        assertEquals(
                """
                Package plan
                Mode: jar
                Archive: %s
                Application output: %s
                Application layout: archive root
                Runtime classpath sidecar: %s
                Dependencies: 2
                - com.example:api:1.2.3 [compile] included -> lib/api-1.2.3.jar rule=thin-runtime-lib lanes=main-compile,main-runtime packageDefault=true lane=application-runtime (compile dependency is copied beside the thin jar) policies=strict-version: com.example:api -> 1.2.3
                - jakarta.servlet:jakarta.servlet-api:6.1.0 [provided] omitted rule=provided-container-omitted lanes=provided packageDefault=false lane=provided-container (provided by container)
                warning CONTAINER_DEPENDENCY_PACKAGED jakarta.servlet:jakarta.servlet-api:6.1.0 rule=war-runtime-lib Container dependency is packaged by a WAR rule.
                  next: Move it to [dependencies.provided], then run `zolt resolve`.
                """
                        .formatted(archive, output, runtimeClasspath),
                formatter.text(plan));
    }

    @Test
    void jsonUsesNullRuntimeClasspathAndEmptyArraysForMinimalPlan() {
        Path archive = tempDir.resolve("target/demo-0.1.0.jar");
        Path output = tempDir.resolve("target/classes");

        PackagePlan plan = new PackagePlan(
                tempDir,
                PackageMode.UBER,
                archive,
                output,
                "archive root",
                Optional.empty(),
                List.of(),
                List.of());

        assertEquals(
                """
                {
                  "mode": "uber-jar",
                  "archive": "%s",
                  "applicationOutput": "%s",
                  "applicationLayout": "archive root",
                  "runtimeClasspath": null,
                  "dependencies": [],
                  "warnings": []
                }
                """
                        .formatted(jsonPath(archive), jsonPath(output)),
                formatter.json(plan));
    }

    @Test
    void jsonEscapesDependencyAndWarningStringFields() {
        Path archive = tempDir.resolve("target/special.jar");
        Path output = tempDir.resolve("target/classes");
        Path runtimeClasspath = tempDir.resolve("target/special.runtime-classpath");
        String special = "quote \" slash \\ backspace \b form \f newline \n return \r tab \t control " + (char) 1;

        PackagePlan plan = new PackagePlan(
                tempDir,
                PackageMode.THIN,
                archive,
                output,
                "archive root",
                Optional.of(runtimeClasspath),
                List.of(new PackagePlanDependency(
                        "com.example:special:1.0.0",
                        "1.0.0",
                        DependencyScope.RUNTIME,
                        List.of("runtime", special),
                        true,
                        special,
                        "included",
                        special,
                        "lib/special.jar",
                        special,
                        List.of(special))),
                List.of(new PackagePlanWarning(
                        "WARN\"1",
                        "subject\\x",
                        "rule\nname",
                        "message\tbody",
                        "next\rstep")));

        String json = formatter.json(plan);
        String escapedSpecial = "quote \\\" slash \\\\ backspace \\b form \\f newline \\n return \\r tab \\t control \\u"
                + "0001";

        assertTrue(json.contains("\"runtimeClasspath\": \"" + jsonPath(runtimeClasspath) + "\""), json);
        assertTrue(json.contains("\"lanes\": [\"runtime\", \"" + escapedSpecial + "\"],"), json);
        assertTrue(json.contains("\"laneDisposition\": \"" + escapedSpecial + "\","), json);
        assertTrue(json.contains("\"rule\": \"" + escapedSpecial + "\","), json);
        assertTrue(json.contains("\"reason\": \"" + escapedSpecial + "\","), json);
        assertTrue(json.contains("\"policies\": [\"" + escapedSpecial + "\"]"), json);
        assertTrue(json.contains("\"code\": \"WARN\\\"1\","), json);
        assertTrue(json.contains("\"subject\": \"subject\\\\x\","), json);
        assertTrue(json.contains("\"rule\": \"rule\\nname\","), json);
        assertTrue(json.contains("\"message\": \"message\\tbody\","), json);
        assertTrue(json.contains("\"nextStep\": \"next\\rstep\""), json);
    }

    private static String jsonPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
