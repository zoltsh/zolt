package sh.zolt.build.run;

import sh.zolt.project.PackageMode;

/**
 * The single launch contract for packaged applications, shared by project and workspace execution.
 */
public final class PackageLaunchPolicy {
    private PackageLaunchPolicy() {}

    public static Decision forMode(PackageMode mode) {
        return switch (mode) {
            case THIN -> Decision.launch(Strategy.CLASSPATH);
            case SPRING_BOOT, SPRING_BOOT_WAR, UBER ->
                    Decision.launch(Strategy.JAR);
            case WAR -> Decision.reject(
                    "Package mode `war` creates a servlet container deployment artifact and cannot be run directly. "
                            + "Deploy it to a servlet container, or use package mode `spring-boot-war` for java -jar.");
            case BOM -> Decision.reject(
                    "Package mode `bom` publishes a dependencyManagement POM and produces no runnable artifact. "
                            + "Import the BOM from an application module via [platforms] instead of running it.");
            case QUARKUS -> Decision.reject(
                    "Package mode `quarkus` uses a fast-JAR layout that `run-package` does not launch. "
                            + "Use `zolt run` for the Quarkus application.");
        };
    }

    public enum Strategy {
        CLASSPATH,
        JAR,
        REJECT
    }

    public record Decision(Strategy strategy, String rejection) {
        private static Decision launch(Strategy strategy) {
            return new Decision(strategy, "");
        }

        private static Decision reject(String message) {
            return new Decision(Strategy.REJECT, message);
        }
    }
}
