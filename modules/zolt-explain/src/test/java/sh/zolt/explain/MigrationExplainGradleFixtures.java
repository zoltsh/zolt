package sh.zolt.explain;

final class MigrationExplainGradleFixtures {
    private MigrationExplainGradleFixtures() {
    }

    static String simpleText() {
        return """
                Zolt explain: Gradle project

                Project
                  Root: $ROOT
                  Settings: settings.gradle
                  Included projects: 0
                  Projects: 1
                  Version catalog aliases: 0
                  Signals: 0

                Projects
                  - . (gradle-simple, dsl=groovy, java=21)
                    build file: build.gradle
                    plugins: 2
                    repositories: 1
                    dependencies: 2

                What Zolt can build
                  ok    no static buildability issues found in this first inspection pass

                What can cache
                  ok    no static cacheability issues found in this first inspection pass

                Non-determinism
                  ok    no static non-determinism issues found in this first inspection pass

                Migration blockers
                  ok    no static migration-blocker issues found in this first inspection pass

                Next steps
                  1. Review the static report, then create zolt.toml and run zolt resolve.

                This command inspected Gradle metadata statically and did not execute Gradle.
                """;
    }

    static String simpleJson() {
        return """
                {
                  "schemaVersion": 1,
                  "source": "gradle",
                  "root": "$ROOT",
                  "settingsFile": "settings.gradle",
                  "summary": {
                    "includedProjects": 0,
                    "projects": 1,
                    "versionCatalogAliases": 0,
                    "signals": 0,
                    "blockers": 0,
                    "warnings": 0,
                    "unknown": 0,
                    "ok": 0
                  },
                  "includedProjects": [],
                  "versionCatalogAliases": [],
                  "projects": [
                    {
                      "path": ".",
                      "name": "gradle-simple",
                      "buildFile": "build.gradle",
                      "dsl": "groovy",
                      "javaVersion": "21",
                      "plugins": [
                        {
                          "id": "application",
                          "version": ""
                        },
                        {
                          "id": "java",
                          "version": ""
                        }
                      ],
                      "repositories": [
                        {
                          "kind": "mavenCentral",
                          "url": "https://repo.maven.apache.org/maven2"
                        }
                      ],
                      "dependencies": [
                        {
                          "configuration": "implementation",
                          "notation": "com.google.guava:guava:33.7.2-jre",
                          "resolvedCoordinate": "com.google.guava:guava:33.7.2-jre",
                          "versionCatalogAlias": ""
                        },
                        {
                          "configuration": "testImplementation",
                          "notation": "org.junit.jupiter:junit-jupiter:5.11.4",
                          "resolvedCoordinate": "org.junit.jupiter:junit-jupiter:5.11.4",
                          "versionCatalogAlias": ""
                        }
                      ],
                      "sourceRoots": [],
                      "testSourceRoots": [],
                      "groovyTestSourceRoots": []
                    }
                  ],
                  "signals": [],
                  "migration": {
                    "status": "ready",
                    "nextSteps": ["Review the static report, then create zolt.toml and run zolt resolve."]
                  }
                }
                """;
    }

    static String enterpriseSpringText() {
        return MigrationExplainGradleEnterpriseFixtures.text();
    }

    static String enterpriseSpringJson() {
        return MigrationExplainGradleEnterpriseFixtures.json();
    }
}
