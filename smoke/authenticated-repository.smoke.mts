import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import {
  AUTHENTICATED_REPOSITORY_PASSWORD_ENV,
  AUTHENTICATED_REPOSITORY_BEARER_ENV,
  AUTHENTICATED_REPOSITORY_USERNAME_ENV,
  installAuthenticatedArtifact,
  writeAuthenticatedConsumer,
  writeBearerAuthenticatedConsumer,
  writeAuthenticatedLibrary,
} from "./support/authenticated-fixtures.mts";
import { startAuthenticatedFileServer, startBearerPartitionedFileServer } from "./support/authenticated-server.mts";
import { expectCommandFailureContains, expectTextFile, packagedZolt, runZolt, singleJar } from "./support/zolt-smoke.mts";

smoke.suite("authenticated repository smoke", { tags: ["repository", "authentication"] }, async (t: SmokeContext) => {
  const work = await t.tempDir("zolt-authenticated-repository");
  const zolt = await packagedZolt(t);
  const credentials = { username: "enterprise-user", password: "enterprise-token" } as const;
  t.redact(credentials.username);
  t.redact(credentials.password);

  await t.step("requires credentials, resolves securely, and consumes the artifact", async () => {
    const repository = work.path("repository");
    const library = work.path("internal-library");
    await writeAuthenticatedLibrary(library);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", library, "--cache-root", zolt.cacheRoot]);
    await installAuthenticatedArtifact(repository, await singleJar(join(library, "target")));

    const server = await startAuthenticatedFileServer(t, repository, credentials);
    const consumer = work.path("consumer");
    await writeAuthenticatedConsumer(consumer, server.url("maven2/"));
    const cache = work.path("cache");

    await expectCommandFailureContains(t, zolt, [
      "--no-progress", "resolve", "--cwd", consumer, "--cache-root", cache,
    ], AUTHENTICATED_REPOSITORY_USERNAME_ENV);

    const credentialEnv = {
      [AUTHENTICATED_REPOSITORY_USERNAME_ENV]: credentials.username,
      [AUTHENTICATED_REPOSITORY_PASSWORD_ENV]: credentials.password,
    };
    await runZolt(t, zolt, [
      "--no-progress", "resolve", "--cwd", consumer, "--cache-root", cache,
    ], { env: credentialEnv });
    await runZolt(t, zolt, [
      "--no-progress", "check", "--context", "ci", "--check", "execution-context", "--cwd", consumer,
    ], { env: credentialEnv });
    await runZolt(t, zolt, [
      "--no-progress", "build", "--cwd", consumer, "--cache-root", cache,
    ], { env: credentialEnv });
    const result = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", consumer, "--cache-root", cache, "--", "Codex",
    ], { env: credentialEnv });

    expect.value(result.stdout).toContain("hello Codex from authenticated repo");
    expect.value(server.requests.join("\n")).toContain("internal-greeting-1.0.0.jar");
    await expectTextFile(join(consumer, "zolt.lock"), {
      excludes: [credentials.username, credentials.password],
    });
  });

  await t.step("isolates a shared cache by resolved bearer credential context", async () => {
    const tokenA = "principal-a-token";
    const tokenB = "principal-b-token";
    t.redact(tokenA);
    t.redact(tokenB);
    const repositoryA = work.path("principal-a-repository");
    const repositoryB = work.path("principal-b-repository");
    const libraryA = work.path("principal-a-library");
    const libraryB = work.path("principal-b-library");
    await writeAuthenticatedLibrary(libraryA, "principal A");
    await writeAuthenticatedLibrary(libraryB, "principal B");
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", libraryA, "--cache-root", zolt.cacheRoot]);
    await runZolt(t, zolt, ["--no-progress", "package", "--cwd", libraryB, "--cache-root", zolt.cacheRoot]);
    await installAuthenticatedArtifact(repositoryA, await singleJar(join(libraryA, "target")), "principal A POM");
    await installAuthenticatedArtifact(repositoryB, await singleJar(join(libraryB, "target")), "principal B POM");

    const server = await startBearerPartitionedFileServer(t, [
      { root: repositoryA, token: tokenA },
      { root: repositoryB, token: tokenB },
    ]);
    const consumerA = work.path("principal-a-consumer");
    const consumerB = work.path("principal-b-consumer");
    await writeBearerAuthenticatedConsumer(consumerA, server.url("maven2/"));
    await writeBearerAuthenticatedConsumer(consumerB, server.url("maven2/"));
    const sharedCache = work.path("principal-shared-cache");
    const envA = { [AUTHENTICATED_REPOSITORY_BEARER_ENV]: tokenA };
    const envB = { [AUTHENTICATED_REPOSITORY_BEARER_ENV]: tokenB };

    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", consumerA, "--cache-root", sharedCache], { env: envA });
    const resultA = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", consumerA, "--cache-root", sharedCache, "--", "ignored",
    ], { env: envA });
    await runZolt(t, zolt, ["--no-progress", "resolve", "--cwd", consumerB, "--cache-root", sharedCache], { env: envB });
    const resultB = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", consumerB, "--cache-root", sharedCache, "--", "ignored",
    ], { env: envB });

    expect.value(resultA.stdout).toContain("principal A");
    expect.value(resultB.stdout).toContain("principal B");
    await expectTextFile(join(consumerA, "zolt.lock"), { excludes: [tokenA, tokenB] });
    await expectTextFile(join(consumerB, "zolt.lock"), { excludes: [tokenA, tokenB] });
  });
});
