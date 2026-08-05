package sh.zolt.build.packaging;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageevidence.PackageArchiveDigests;
import sh.zolt.build.packageplan.PackageInputBudget;
import sh.zolt.build.packageplan.PackageInputSnapshot;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PackageModePackagerRegistryTest {
    @Test
    void dispatchesRegisteredPackageMode() {
        AtomicReference<PackageAssemblyRequest> captured = new AtomicReference<>();
        PackageResult expected = packageResult(PackageMode.THIN);
        PackageModePackagerRegistry registry = PackageModePackagerRegistry.of(Map.of(
                PackageMode.THIN,
                request -> {
                    captured.set(request);
                    return expected;
                }));
        PackageAssemblyRequest request = request(PackageMode.THIN);

        PackageResult result = registry.assemble(request);

        assertSame(expected, result);
        assertSame(request, captured.get());
    }

    @Test
    void reportsMissingPackageModeRegistration() {
        PackageModePackagerRegistry registry = PackageModePackagerRegistry.of(Map.of(
                PackageMode.THIN,
                request -> packageResult(PackageMode.THIN)));

        PackageException exception = assertThrows(
                PackageException.class,
                () -> registry.assemble(request(PackageMode.QUARKUS)));

        assertTrue(exception.getMessage().contains("No primary artifact packager is registered"));
        assertTrue(exception.getMessage().contains("`quarkus`"));
    }

    @Test
    void listsSupportedModesInEnumOrder() {
        Map<PackageMode, PackageModePackager> packagers = new LinkedHashMap<>();
        packagers.put(PackageMode.UBER, request -> packageResult(PackageMode.UBER));
        packagers.put(PackageMode.THIN, request -> packageResult(PackageMode.THIN));
        packagers.put(PackageMode.QUARKUS, request -> packageResult(PackageMode.QUARKUS));
        PackageModePackagerRegistry registry = PackageModePackagerRegistry.of(packagers);

        assertEquals(
                List.of(PackageMode.THIN, PackageMode.QUARKUS, PackageMode.UBER),
                registry.supportedModes());
    }

    private static PackageAssemblyRequest request(PackageMode mode) {
        return new PackageAssemblyRequest(
                Path.of("project"),
                projectConfig(mode),
                buildResult(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                PackageInputSnapshot.of(
                        Path.of("project/target/classes"),
                        PackageInputBudget.streaming()),
                new PackageArchiveDigests());
    }

    private static ProjectConfig projectConfig(PackageMode mode) {
        return config(Optional.empty())
                .withPackageSettings(new PackageSettings(mode));
    }

    private static BuildResult buildResult() {
        return new BuildResult(
                Optional.empty(),
                1,
                0,
                Path.of("project/target/classes"),
                "");
    }

    private static PackageResult packageResult(PackageMode mode) {
        return new PackageResult(
                buildResult(),
                mode,
                Path.of("project/target/demo.jar"),
                Optional.empty(),
                1,
                false);
    }
}
