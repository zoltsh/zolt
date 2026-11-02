package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.PackageException;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspacePackageJavadocToolchainTest {
    @TempDir
    private Path root;

    @Test
    void memberDocumentationUsesItsSelectedToolchain() throws Exception {
        Path workspace = workspace();
        Path selected = selectedJdk(true);
        var result = new WorkspacePackageService().withJdkCheckers((context, member) -> checker(selected))
                .packageJars(workspace, root.resolve("cache"), selection());

        assertEquals(1, result.members().size());
        assertTrue(Files.readString(root.resolve("javadoc-arguments.txt")).contains("-quiet"));
        try (JarFile docs = new JarFile(workspace.resolve("modules/lib/target/lib-1.0.0-javadoc.jar").toFile())) {
            assertNotNull(docs.getEntry("review/Api.html"));
        }
    }

    @Test
    void unavailableMemberJavadocFailsBeforeBuildOrPackageOutputsChange() throws Exception {
        Path workspace = workspace();
        Path selected = selectedJdk(false);
        Path classes = workspace.resolve("modules/lib/target/classes/keep.txt");
        Files.createDirectories(classes.getParent());
        Files.writeString(classes, "previous classes");
        Path archive = workspace.resolve("modules/lib/target/lib-1.0.0.jar");
        Files.writeString(archive, "previous archive");

        PackageException failure = assertThrows(PackageException.class, () ->
                new WorkspacePackageService().withJdkCheckers((context, member) -> checker(selected))
                        .packageJars(workspace, root.resolve("cache"), selection()));

        assertTrue(failure.getMessage().contains(selected.resolve("bin/javadoc").toString()));
        assertEquals("previous classes", Files.readString(classes));
        assertEquals("previous archive", Files.readString(archive));
    }

    private Path workspace() throws Exception {
        Path workspace = root.resolve("workspace");
        Path member = workspace.resolve("modules/lib");
        Path source = member.resolve("src/main/java/review/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "docs-workspace"
                [workspace.members]
                include = ["modules/lib"]
                """);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "lib"
                version = "1.0.0"
                group = "review"
                java = 21
                [package]
                sources = true
                javadoc = true
                """);
        Files.writeString(source, "package review; /** API documentation. */ public final class Api {}\n");
        return workspace;
    }

    private Path selectedJdk(boolean includeJavadoc) throws Exception {
        Path home = root.resolve("member JDK");
        Files.createDirectories(home.resolve("bin"));
        Path runtime = Path.of(System.getProperty("java.home"), "bin");
        for (String tool : List.of("java", "javac", "jar")) {
            Files.createSymbolicLink(home.resolve("bin").resolve(tool), runtime.resolve(tool));
        }
        if (includeJavadoc) {
            Path javadoc = home.resolve("bin/javadoc");
            Files.writeString(javadoc, "#!/bin/sh\nprintf '%s\\n' \"$@\" >> "
                    + quote(root.resolve("javadoc-arguments.txt")) + "\nexec "
                    + quote(runtime.resolve("javadoc")) + " \"$@\"\n");
            assertTrue(javadoc.toFile().setExecutable(true));
        }
        return home;
    }

    private static JdkChecker checker(Path home) {
        return required -> new JdkStatus(Optional.of(home), Optional.of(home.resolve("bin/java")),
                Optional.of(home.resolve("bin/javac")), Optional.of(home.resolve("bin/jar")),
                Optional.of("21"), Optional.of("selected-member-jdk"), required);
    }

    private static WorkspaceSelectionRequest selection() {
        return new WorkspaceSelectionRequest(false, List.of("modules/lib"));
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
