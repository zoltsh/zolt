package sh.zolt.build.packaging;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class PackageNestedArtifactAuthorityTestFixtures {
    private PackageNestedArtifactAuthorityTestFixtures() {
    }

    static List<LockPackage> packages(PackageMode mode) {
        List<LockPackage> packages = new ArrayList<>();
        packages.add(lockPackage(
                "com.example",
                "native",
                "1.0.0",
                DependencyScope.PROVIDED,
                "com/example/native/1.0.0/native-1.0.0.jar"));
        packages.add(lockPackage(
                "com.example",
                "native",
                "1.0.0",
                DependencyScope.RUNTIME,
                "com/example/native/1.0.0/native-1.0.0-linux.jar"));
        packages.add(lockPackage(
                "com.example",
                "native",
                "1.0.0",
                DependencyScope.RUNTIME,
                "com/example/native/1.0.0/native-1.0.0-macos.jar"));
        packages.add(lockPackage(
                "com.bridge",
                "native",
                "1.0.0",
                DependencyScope.PROVIDED,
                "com/bridge/native/1.0.0/native-1.0.0-linux.jar"));
        packages.add(lockPackage(
                "com.bridge",
                "native",
                "1.0.0",
                DependencyScope.RUNTIME,
                "com/bridge/native/1.0.0/native-1.0.0.jar"));
        packages.add(lockPackage(
                "com.alpha",
                "shared",
                "1.0.0",
                DependencyScope.RUNTIME,
                "com/alpha/shared/1.0.0/shared-1.0.0.jar"));
        packages.add(lockPackage(
                "com.beta",
                "shared",
                "1.0.0",
                DependencyScope.RUNTIME,
                "com/beta/shared/1.0.0/shared-1.0.0.jar"));
        packages.add(lockPackage(
                "com.transitive",
                "shared",
                "1.0.0",
                DependencyScope.PROVIDED,
                false,
                "com/transitive/shared/1.0.0/shared-1.0.0.jar"));
        packages.add(lockPackage(
                "com.transitive",
                "shared",
                "1.0.0",
                DependencyScope.RUNTIME,
                false,
                "com/transitive/shared/1.0.0/shared-1.0.0.jar"));
        packages.add(lockPackage(
                "com.transitive",
                "container-api",
                "1.0.0",
                DependencyScope.PROVIDED,
                false,
                "com/transitive/container-api/1.0.0/container-api-1.0.0.jar"));
        packages.add(lockPackage(
                "com.direct",
                "shared",
                "1.0.0",
                DependencyScope.PROVIDED,
                true,
                "com/direct/shared/1.0.0/shared-1.0.0.jar"));
        packages.add(lockPackage(
                "com.direct",
                "shared",
                "1.0.0",
                DependencyScope.RUNTIME,
                false,
                "com/direct/shared/1.0.0/shared-1.0.0.jar"));
        if (mode == PackageMode.SPRING_BOOT
                || mode == PackageMode.SPRING_BOOT_WAR) {
            addSpringBootPackages(packages, mode);
        }
        return List.copyOf(packages);
    }

    static ProjectConfig config(PackageMode mode) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [dependencies.provided]
                "com.example:native" = "1.0.0"
                "com.bridge:native" = { version = "1.0.0", classifier = "linux" }
                "com.direct:shared" = "1.0.0"
                %s
                [package]
                mode = "%s"
                """.formatted(
                System.getProperty("java.specification.version"),
                mode == PackageMode.SPRING_BOOT_WAR
                        ? "\"org.springframework.boot:spring-boot-loader\" = \"4.0.6\"\n"
                        : "",
                manifestMode(mode)));
    }

    /** Legacy {@link PackageMode} to its final {@code [package].mode} symbol (design §17.2). */
    static String manifestMode(PackageMode mode) {
        return switch (mode) {
            case THIN -> "jar";
            case UBER -> "uber-jar";
            default -> mode.configValue();
        };
    }

    static LockPackage lockPackage(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            String jar) {
        return lockPackage(
                group,
                artifact,
                version,
                scope,
                scope == DependencyScope.PROVIDED,
                jar);
    }

    static LockPackage lockPackage(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            boolean direct,
            String jar) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "maven-central",
                scope,
                direct,
                Optional.of(jar),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    static String lockfile(List<LockPackage> packages) {
        StringBuilder lockfile = new StringBuilder("version = 7\n");
        for (LockPackage lockPackage : packages) {
            lockfile.append("\n[[package]]\n")
                    .append("id = \"")
                    .append(lockPackage.packageId())
                    .append("\"\nversion = \"")
                    .append(lockPackage.version())
                    .append("\"\nsource = \"maven-central\"\nscope = \"")
                    .append(lockPackage.scope().lockfileName())
                    .append("\"\ndirect = ")
                    .append(lockPackage.direct())
                    .append("\njar = \"")
                    .append(lockPackage.jar().orElseThrow())
                    .append("\"\ndependencies = []\n");
        }
        for (LockPackage lockPackage : packages) {
            if (!lockPackage.direct()) {
                continue;
            }
            LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
            lockfile.append("\n[[dependencyRoot]]\nmember = \".\"\nid = \"")
                    .append(lockPackage.packageId())
                    .append("\"\nversion = \"")
                    .append(lockPackage.version())
                    .append("\"\nlane = \"provided\"\n");
            if (!variant.isDefault()) {
                lockfile.append("variant = \"").append(variant.key()).append("\"\n");
            }
            lockfile.append("resolvedScope = \"provided\"\n");
        }
        return lockfile.toString();
    }

    private static void addSpringBootPackages(
            List<LockPackage> packages,
            PackageMode mode) {
        packages.add(lockPackage(
                "org.springframework.boot",
                "spring-boot",
                "4.0.6",
                DependencyScope.COMPILE,
                "org/springframework/boot/spring-boot/4.0.6/spring-boot-4.0.6.jar"));
        packages.add(lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar"));
        packages.add(lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6-tests.jar"));
        packages.add(lockPackage(
                "org.springframework.boot",
                "spring-boot-loader",
                "4.0.6",
                DependencyScope.RUNTIME,
                "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6-fixtures.jar"));
        if (mode == PackageMode.SPRING_BOOT_WAR) {
            packages.add(lockPackage(
                    "org.springframework.boot",
                    "spring-boot-loader",
                    "4.0.6",
                    DependencyScope.PROVIDED,
                    true,
                    "org/springframework/boot/spring-boot-loader/4.0.6/spring-boot-loader-4.0.6.jar"));
        }
    }
}
