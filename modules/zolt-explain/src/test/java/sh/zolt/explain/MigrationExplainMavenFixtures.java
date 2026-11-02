package sh.zolt.explain;

final class MigrationExplainMavenFixtures {
    private MigrationExplainMavenFixtures() {
    }

    static String simpleText() {
        return """
                Zolt explain: Maven project

                Project
                  Root: $ROOT
                  Projects: 1
                  Signals: 0

                Projects
                  - . (maven-simple, packaging=jar, java=21)
                    coordinates: dev.zolt.examples:maven-simple:1.0.0
                    dependencies: 2
                      - compile com.google.guava:guava:33.7.2-jre
                      - test org.junit.jupiter:junit-jupiter:5.11.4
                    managed dependencies: 0
                    imported BOMs: 0
                    annotation processors: 0
                    plugins: 0
                    profiles: 0

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

                This command inspected Maven metadata statically and did not execute Maven.
                """;
    }

    static String simpleJson() {
        return """
                {
                  "schemaVersion": 1,
                  "source": "maven",
                  "root": "$ROOT",
                  "summary": {
                    "projects": 1,
                    "signals": 0,
                    "blockers": 0,
                    "warnings": 0,
                    "unknown": 0,
                    "ok": 0
                  },
                  "projects": [
                    {
                      "path": ".",
                      "name": "maven-simple",
                      "groupId": "dev.zolt.examples",
                      "version": "1.0.0",
                      "displayName": "",
                      "packaging": "jar",
                      "javaVersion": "21",
                      "parents": [],
                      "modules": [],
                      "sourceRoots": [],
                      "testSourceRoots": [],
                      "resourceRoots": [],
                      "dependencies": [
                        {
                          "scope": "compile",
                          "coordinate": "com.google.guava:guava:33.7.2-jre",
                          "version": "33.7.2-jre",
                          "type": "jar",
                          "optional": false,
                          "managed": false,
                          "importedBom": false
                        },
                        {
                          "scope": "test",
                          "coordinate": "org.junit.jupiter:junit-jupiter:5.11.4",
                          "version": "5.11.4",
                          "type": "jar",
                          "optional": false,
                          "managed": false,
                          "importedBom": false
                        }
                      ],
                      "dependencyManagement": [],
                      "importedBoms": [],
                      "annotationProcessors": [],
                      "repositories": [],
                      "plugins": [],
                      "profiles": []
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
}
