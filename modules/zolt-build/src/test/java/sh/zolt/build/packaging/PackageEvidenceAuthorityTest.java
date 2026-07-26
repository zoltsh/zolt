package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static sh.zolt.build.packaging.PackageServiceTestSupport.source;
import static sh.zolt.build.packaging.PackageServiceTestSupport.writeLockfile;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanMaterializedInput;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackageEvidenceAuthorityTest {
    @TempDir
    private Path tempDir;

    @Test
    void rejectsExtraAndChangedArtifactClassifiers() throws IOException {
        Fixture fixture = thinFixture("artifact-classifiers");
        String json = Files.readString(fixture.evidence());
        String mainArtifact = firstArrayObject(json, "\"artifacts\"");

        String extra = mainArtifact.replace(
                "\"classifier\": \"main\"",
                "\"classifier\": \"extra\"");
        writeTampered(
                fixture,
                insertFirstArrayObject(json, "\"artifacts\"", extra));
        assertProblem(fixture, "artifact set");

        writeTampered(
                fixture,
                replaceFirstArrayObject(
                        json,
                        "\"artifacts\"",
                        mainArtifact.replace(
                                "\"classifier\": \"main\"",
                                "\"classifier\": \"renamed\"")));
        assertProblem(fixture, "artifact set");
    }

    @Test
    void rejectsDuplicateArtifactClassifierAndAbsoluteArtifactPath()
            throws IOException {
        Fixture fixture = thinFixture("duplicate-and-absolute");
        String json = Files.readString(fixture.evidence());
        String mainArtifact = firstArrayObject(json, "\"artifacts\"");

        writeTampered(
                fixture,
                insertFirstArrayObject(
                        json,
                        "\"artifacts\"",
                        mainArtifact));
        assertProblem(fixture, "artifact classifier `main` more than once");

        String absolute = mainArtifact.replace(
                "\"path\": \"target/demo-0.1.0.jar\"",
                "\"path\": \"" + fixture.plan().archivePath() + "\"");
        writeTampered(
                fixture,
                replaceFirstArrayObject(
                        json,
                        "\"artifacts\"",
                        absolute));
        assertProblem(fixture, "must be project-relative");
    }

    @Test
    void rejectsArtifactThatIsAbsentFromTheAuthoritativeOutputSet()
            throws IOException {
        Fixture fixture = thinFixture("artifact-without-output");
        String json = Files.readString(fixture.evidence());
        writeTampered(
                fixture,
                removeFirstArrayObject(json, "\"outputs\""));

        assertProblem(fixture, "artifact `main` is absent from package outputs");
    }

    @Test
    void requiresTheExactPlannedMaterializedInputSetAndStableIdentity()
            throws IOException {
        WorkspaceFixture fixture = workspaceFixture("materialized-set");
        String json = Files.readString(fixture.fixture().evidence());
        assertFalse(
                json.contains(fixture.workspaceRoot().toString()),
                json);
        writeTampered(
                fixture.fixture(),
                removeFirstArrayObject(
                        json,
                        "\"materializedInputs\""));

        assertProblem(
                fixture.fixture(),
                "materialized input set");
    }

    @Test
    void workspaceProviderBytesInvalidateEvidenceWithoutCheckoutBoundPaths()
            throws IOException {
        WorkspaceFixture fixture = workspaceFixture("workspace-provider");
        Files.writeString(
                fixture.providerClass(),
                "changed provider bytecode");
        PackagePlan current = new PackagePlanService().plan(
                fixture.fixture().root(),
                fixture.fixture().config(),
                fixture.lockfile());
        List<String> problems = new PackageEvidenceVerifier()
                .verify(
                        fixture.fixture().root(),
                        current,
                        fixture.fixture().evidence())
                .problems();

        assertTrue(
                problems.stream().anyMatch(problem ->
                        problem.contains("workspace package input")
                                && problem.contains("changed")),
                problems.toString());
    }

    @Test
    void materializedEvidenceVerifiesAfterMovingTheCheckout()
            throws IOException {
        WorkspaceFixture original = workspaceFixture("portable-original");
        Path movedWorkspace = tempDir.resolve("portable-moved");
        copyTree(original.workspaceRoot(), movedWorkspace);
        Path movedConsumer = movedWorkspace.resolve("consumer");
        PackagePlan movedPlan = new PackagePlanService().plan(
                movedConsumer,
                original.fixture().config(),
                original.lockfile());
        Path movedEvidence = movedConsumer.resolve(
                original.fixture().root().relativize(
                        original.fixture().evidence()));
        List<String> problems = new PackageEvidenceVerifier()
                .verify(movedConsumer, movedPlan, movedEvidence)
                .problems();

        assertTrue(problems.isEmpty(), problems.toString());
    }

    private Fixture thinFixture(String name) throws IOException {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root);
        writeLockfile(root);
        source(root, "src/main/java/com/example/Main.java", """
                package com.example;
                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
        ProjectConfig config = config(Optional.of("com.example.Main"));
        PackageResult result = new PackageService().packageJar(
                root,
                config,
                root.resolve("cache"));
        PackagePlan plan = new PackagePlanService().plan(root, config);
        return new Fixture(
                root,
                config,
                plan,
                result.evidenceManifestPath().orElseThrow());
    }

    private WorkspaceFixture workspaceFixture(String name)
            throws IOException {
        Path workspaceRoot = tempDir.resolve(name);
        Path consumer = workspaceRoot.resolve("consumer");
        Files.createDirectories(consumer);
        Path providerClass = workspaceRoot.resolve(
                "provider/target/classes/com/example/Provider.class");
        Files.createDirectories(providerClass.getParent());
        Files.writeString(providerClass, "provider bytecode");
        ProjectConfig config = config(Optional.of("com.example.Main"))
                .withPackageSettings(new PackageSettings(
                        PackageMode.SPRING_BOOT));
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(new LockPackage(
                        new PackageId("com.example", "provider"),
                        "1.0.0",
                        "workspace",
                        DependencyScope.COMPILE,
                        true,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of("provider"),
                        Optional.of("target/classes"),
                        List.of())),
                List.of());
        PackagePlan plan = new PackagePlanService().plan(
                consumer,
                config,
                lockfile);
        Files.createDirectories(plan.archivePath().getParent());
        Files.writeString(plan.archivePath(), "spring boot archive");
        PackagePlanMaterializedInput expected =
                plan.evidence().materializedInputs().getFirst();
        Files.createDirectories(expected.jarPath().getParent());
        Files.writeString(expected.jarPath(), "staged provider jar");
        PackageMaterializedInput materialized =
                new PackageMaterializedInput(
                        expected.coordinate(),
                        expected.sourceDirectory(),
                        expected.jarPath(),
                        expected.sourceFingerprint(),
                        sha256(expected.jarPath()));
        PackageResult result = new PackageResult(
                        new BuildResult(
                                Optional.empty(),
                                0,
                                0,
                                plan.applicationOutput(),
                                ""),
                        PackageMode.SPRING_BOOT,
                        plan.archivePath(),
                        Optional.empty(),
                        Optional.empty(),
                        1,
                        true,
                        plan.applicationLayout(),
                        List.of(),
                        List.of())
                .withMaterializedInputs(List.of(materialized));
        Path evidence = new PackageEvidenceManifestWriter().write(
                consumer,
                config,
                plan,
                result,
                List.of());
        return new WorkspaceFixture(
                workspaceRoot,
                providerClass,
                lockfile,
                new Fixture(consumer, config, plan, evidence));
    }

    private static void assertProblem(
            Fixture fixture,
            String expected) {
        List<String> problems = new PackageEvidenceVerifier()
                .verify(
                        fixture.root(),
                        fixture.plan(),
                        fixture.evidence())
                .problems();
        assertTrue(
                problems.stream().anyMatch(problem ->
                        problem.contains(expected)),
                problems.toString());
    }

    private static void writeTampered(
            Fixture fixture,
            String json) throws IOException {
        Files.writeString(fixture.evidence(), json);
    }

    private static String firstArrayObject(
            String json,
            String arrayName) {
        int array = json.indexOf(arrayName);
        int start = json.indexOf('{', json.indexOf('[', array));
        int end = matchingBrace(json, start) + 1;
        return json.substring(start, end);
    }

    private static String replaceFirstArrayObject(
            String json,
            String arrayName,
            String replacement) {
        int array = json.indexOf(arrayName);
        int start = json.indexOf('{', json.indexOf('[', array));
        int end = matchingBrace(json, start) + 1;
        return json.substring(0, start)
                + replacement
                + json.substring(end);
    }

    private static String insertFirstArrayObject(
            String json,
            String arrayName,
            String object) {
        int array = json.indexOf(arrayName);
        int start = json.indexOf('{', json.indexOf('[', array));
        return json.substring(0, start)
                + object
                + ",\n"
                + json.substring(start);
    }

    private static String removeFirstArrayObject(
            String json,
            String arrayName) {
        int array = json.indexOf(arrayName);
        int arrayStart = json.indexOf('[', array);
        int start = json.indexOf('{', arrayStart);
        int end = matchingBrace(json, start) + 1;
        int next = end;
        while (next < json.length()
                && Character.isWhitespace(json.charAt(next))) {
            next++;
        }
        if (next < json.length() && json.charAt(next) == ',') {
            next++;
        } else {
            int previous = start - 1;
            while (previous > arrayStart
                    && Character.isWhitespace(json.charAt(previous))) {
                previous--;
            }
            if (json.charAt(previous) == ',') {
                start = previous;
            }
        }
        return json.substring(0, start) + json.substring(next);
    }

    private static int matchingBrace(String json, int start) {
        int depth = 0;
        boolean string = false;
        boolean escape = false;
        for (int index = start; index < json.length(); index++) {
            char current = json.charAt(index);
            if (string) {
                if (escape) {
                    escape = false;
                } else if (current == '\\') {
                    escape = true;
                } else if (current == '"') {
                    string = false;
                }
                continue;
            }
            if (current == '"') {
                string = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        throw new AssertionError("Unterminated JSON object.");
    }

    private static String sha256(Path path) throws IOException {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is required.", exception);
        }
    }

    private static void copyTree(Path source, Path target)
            throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    private record Fixture(
            Path root,
            ProjectConfig config,
            PackagePlan plan,
            Path evidence) {
    }

    private record WorkspaceFixture(
            Path workspaceRoot,
            Path providerClass,
            ZoltLockfile lockfile,
            Fixture fixture) {
    }
}
