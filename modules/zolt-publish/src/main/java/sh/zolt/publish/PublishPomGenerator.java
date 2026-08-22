package sh.zolt.publish;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BomSettings;
import sh.zolt.project.DependencyExclusionSpec;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.DeveloperEntry;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.dependency.PackageId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PublishPomGenerator {
    public String generate(ProjectConfig config, ZoltLockfile lockfile) {
        PublishDependencyRootCoverage.requireComplete(config, lockfile);
        StringBuilder xml = new StringBuilder();
        writeProjectHeader(xml, config);
        if (config.packageSettings().mode() == PackageMode.BOM) {
            writeDependencyManagement(xml, config, lockfile);
        } else {
            writeDependencies(xml, config, lockfile);
        }
        xml.append("</project>\n");
        return xml.toString();
    }

    private static void writeProjectHeader(StringBuilder xml, ProjectConfig config) {
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" ")
                .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
                .append("xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        PublicationMetadata metadata = config.packageSettings().metadata();
        element(xml, 1, "modelVersion", "4.0.0");
        element(xml, 1, "groupId", config.project().group());
        element(xml, 1, "artifactId", config.project().name());
        element(xml, 1, "version", config.project().version());
        String packaging = packaging(config.packageSettings().mode());
        if (!packaging.equals("jar")) {
            element(xml, 1, "packaging", packaging);
        }
        if (!metadata.name().isBlank()) {
            element(xml, 1, "name", metadata.name());
        }
        if (!metadata.description().isBlank()) {
            element(xml, 1, "description", metadata.description());
        }
        if (!metadata.url().isBlank()) {
            element(xml, 1, "url", metadata.url());
        }
        if (!metadata.license().isBlank()) {
            indent(xml, 1).append("<licenses>\n");
            indent(xml, 2).append("<license>\n");
            element(xml, 3, "name", metadata.license());
            if (!metadata.licenseUrl().isBlank()) {
                element(xml, 3, "url", metadata.licenseUrl());
            }
            indent(xml, 2).append("</license>\n");
            indent(xml, 1).append("</licenses>\n");
        }
        if (!metadata.developers().isEmpty() || !metadata.developerEntries().isEmpty()) {
            indent(xml, 1).append("<developers>\n");
            for (DeveloperEntry developer : metadata.developerEntries()) {
                developer(xml, developer);
            }
            for (String developer : metadata.developers()) {
                indent(xml, 2).append("<developer>\n");
                element(xml, 3, "name", developer);
                indent(xml, 2).append("</developer>\n");
            }
            indent(xml, 1).append("</developers>\n");
        }
        if (!metadata.scm().isBlank()
                || !metadata.scmConnection().isBlank()
                || !metadata.scmDeveloperConnection().isBlank()
                || !metadata.scmTag().isBlank()) {
            indent(xml, 1).append("<scm>\n");
            if (!metadata.scmConnection().isBlank()) {
                element(xml, 2, "connection", metadata.scmConnection());
            }
            if (!metadata.scmDeveloperConnection().isBlank()) {
                element(xml, 2, "developerConnection", metadata.scmDeveloperConnection());
            }
            if (!metadata.scmTag().isBlank()) {
                element(xml, 2, "tag", metadata.scmTag());
            }
            if (!metadata.scm().isBlank()) {
                element(xml, 2, "url", metadata.scm());
            }
            indent(xml, 1).append("</scm>\n");
        }
        if (!metadata.issues().isBlank()) {
            indent(xml, 1).append("<issueManagement>\n");
            element(xml, 2, "url", metadata.issues());
            indent(xml, 1).append("</issueManagement>\n");
        }
    }

    private static void writeDependencies(StringBuilder xml, ProjectConfig config, ZoltLockfile lockfile) {
        List<PublishPomDependency> dependencies = dependencies(config, lockfile);
        if (dependencies.isEmpty()) {
            return;
        }
        indent(xml, 1).append("<dependencies>\n");
        for (PublishPomDependency dependency : dependencies) {
            dependency(xml, 2, dependency);
        }
        indent(xml, 1).append("</dependencies>\n");
    }

    private static void writeDependencyManagement(StringBuilder xml, ProjectConfig config, ZoltLockfile lockfile) {
        List<PublishPomDependency> managed = managedDependencies(config, lockfile);
        if (managed.isEmpty()) {
            return;
        }
        indent(xml, 1).append("<dependencyManagement>\n");
        indent(xml, 2).append("<dependencies>\n");
        for (PublishPomDependency dependency : managed) {
            dependency(xml, 3, dependency);
        }
        indent(xml, 2).append("</dependencies>\n");
        indent(xml, 1).append("</dependencyManagement>\n");
    }

    private static List<PublishPomDependency> dependencies(ProjectConfig config, ZoltLockfile lockfile) {
        Map<String, PublishPomDependency> dependencies = new LinkedHashMap<>();
        for (LockDependencyRoot root : lockfile.dependencyRoots()) {
            if (!publishedLane(root.lane())) {
                continue;
            }
            String coordinate = coordinate(root.packageId());
            DependencyMetadata metadata =
                    config.dependencyMetadata().get(PublishDependencyMetadataKey.of(root.lane(), coordinate));
            // Key by the variant-aware identity so a plain jar and a classified jar at one GA both survive
            // as distinct POM dependencies instead of the later overwriting the earlier.
            PublishPomDependency dependency = new PublishPomDependency(
                    root.packageId().groupId(),
                    root.packageId().artifactId(),
                    root.variant().classifier().orElse(null),
                    root.version(),
                    root.variant().extension().equals("jar") ? null : root.variant().extension(),
                    mavenScope(root.lane()),
                    root.optional(),
                    metadata == null ? List.of() : metadata.exclusions());
            dependencies.put(dependency.coordinate(), dependency);
        }
        return dependencies.values().stream()
                .sorted(Comparator.comparing(PublishPomDependency::coordinate))
                .toList();
    }

    private static List<PublishPomDependency> managedDependencies(ProjectConfig config, ZoltLockfile lockfile) {
        List<PublishPomDependency> managed = new ArrayList<>();
        // Workspace family members: real GAV at the locked version (fact 5).
        for (LockPackage lockPackage : lockfile.packages()) {
            if (lockPackage.workspace().isEmpty()) {
                continue;
            }
            managed.add(new PublishPomDependency(
                    lockPackage.packageId().groupId(),
                    lockPackage.packageId().artifactId(),
                    null,
                    lockPackage.version(),
                    null,
                    "compile",
                    false,
                    List.of()));
        }
        BomSettings bom = config.packageSettings().bom();
        for (BomSettings.ManagedVersion pin : bom.versions()) {
            PackageId packageId = packageId(pin.coordinate());
            managed.add(new PublishPomDependency(
                    packageId.groupId(),
                    packageId.artifactId(),
                    pin.classifier(),
                    pin.version(),
                    pin.type(),
                    "compile",
                    false,
                    List.of()));
        }
        for (BomSettings.ImportedBom imported : bom.imports()) {
            PackageId packageId = packageId(imported.coordinate());
            managed.add(new PublishPomDependency(
                    packageId.groupId(),
                    packageId.artifactId(),
                    null,
                    imported.version(),
                    "pom",
                    "import",
                    false,
                    List.of()));
        }
        return managed.stream()
                .sorted(Comparator.comparing(PublishPomDependency::groupId)
                        .thenComparing(PublishPomDependency::artifactId)
                        .thenComparing(dependency -> dependency.classifier() == null ? "" : dependency.classifier()))
                .toList();
    }

    private static boolean publishedLane(DependencyLane lane) {
        return switch (lane) {
            case API, IMPLEMENTATION, RUNTIME, PROVIDED -> true;
            case DEV, TEST, PROCESSOR, TEST_PROCESSOR -> false;
        };
    }

    private static String mavenScope(DependencyLane lane) {
        return switch (lane) {
            case API -> "compile";
            case IMPLEMENTATION, RUNTIME -> "runtime";
            case PROVIDED -> "provided";
            case DEV, TEST, PROCESSOR, TEST_PROCESSOR -> throw new PublishException(
                    "Dependency lane `" + lane.name().toLowerCase().replace('_', '-')
                            + "` does not have a published Maven scope.");
        };
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new PublishException("Invalid dependency coordinate `" + coordinate + "` in publication metadata.");
        }
        return new PackageId(parts[0], parts[1]);
    }

    private static String packaging(PackageMode mode) {
        return switch (mode) {
            case WAR, SPRING_BOOT_WAR -> "war";
            case BOM -> "pom";
            default -> "jar";
        };
    }

    private static void developer(StringBuilder xml, DeveloperEntry developer) {
        indent(xml, 2).append("<developer>\n");
        if (!developer.id().isBlank()) {
            element(xml, 3, "id", developer.id());
        }
        if (!developer.name().isBlank()) {
            element(xml, 3, "name", developer.name());
        }
        if (!developer.email().isBlank()) {
            element(xml, 3, "email", developer.email());
        }
        if (!developer.organization().isBlank()) {
            element(xml, 3, "organization", developer.organization());
        }
        if (!developer.url().isBlank()) {
            element(xml, 3, "url", developer.url());
        }
        indent(xml, 2).append("</developer>\n");
    }

    private static void dependency(StringBuilder xml, int level, PublishPomDependency dependency) {
        indent(xml, level).append("<dependency>\n");
        element(xml, level + 1, "groupId", dependency.groupId());
        element(xml, level + 1, "artifactId", dependency.artifactId());
        if (dependency.classifier() != null && !dependency.classifier().isBlank()) {
            element(xml, level + 1, "classifier", dependency.classifier());
        }
        if (dependency.version() != null && !dependency.version().isBlank()) {
            element(xml, level + 1, "version", dependency.version());
        }
        if (dependency.type() != null && !dependency.type().isBlank()) {
            element(xml, level + 1, "type", dependency.type());
        }
        if (!dependency.scope().equals("compile")) {
            element(xml, level + 1, "scope", dependency.scope());
        }
        if (dependency.optional()) {
            element(xml, level + 1, "optional", "true");
        }
        if (!dependency.exclusions().isEmpty()) {
            indent(xml, level + 1).append("<exclusions>\n");
            for (DependencyExclusionSpec exclusion : dependency.exclusions()) {
                indent(xml, level + 2).append("<exclusion>\n");
                element(xml, level + 3, "groupId", exclusion.group());
                element(xml, level + 3, "artifactId", exclusion.artifact());
                indent(xml, level + 2).append("</exclusion>\n");
            }
            indent(xml, level + 1).append("</exclusions>\n");
        }
        indent(xml, level).append("</dependency>\n");
    }

    private static void element(StringBuilder xml, int level, String name, String value) {
        indent(xml, level)
                .append('<')
                .append(name)
                .append('>')
                .append(escapeXml(value))
                .append("</")
                .append(name)
                .append(">\n");
    }

    private static StringBuilder indent(StringBuilder xml, int level) {
        return xml.append("  ".repeat(level));
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String coordinate(PackageId packageId) {
        return packageId.groupId() + ":" + packageId.artifactId();
    }
}
