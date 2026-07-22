import type { TextFileContract } from "./assertions.mts";

export interface ResolverFixtureContract {
  readonly name: string;
  readonly lockfile: TextFileContract;
}

export const RESOLVER_FIXTURE_CONTRACTS: readonly ResolverFixtureContract[] = [
  {
    name: "adoption-plain-app",
    lockfile: { contains: ['id = "com.google.guava:guava"', 'id = "org.apache.commons:commons-lang3"'] },
  },
  {
    name: "junit-basic",
    lockfile: { contains: ['id = "org.junit.platform:junit-platform-console-standalone"'] },
  },
  {
    name: "spring-boot-webmvc",
    lockfile: {
      contains: ['id = "org.springframework.boot:spring-boot-starter-webmvc"'],
      excludes: ['id = "org.springframework.boot:spring-boot-dependencies"'],
    },
  },
  {
    name: "micronaut-http",
    lockfile: { contains: ['id = "io.micronaut:micronaut-runtime"', 'id = "io.micronaut:micronaut-inject-java"'] },
  },
  {
    name: "hikaricp-canary",
    lockfile: {
      contains: ['id = "org.slf4j:slf4j-api"', 'kind = "edge-exclusion"', 'id = "org.hamcrest:hamcrest-core"'],
      excludes: ['id = "org.checkerframework:checker-qual"'],
    },
  },
];
