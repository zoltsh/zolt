import { expect, smoke, type SmokeContext } from "smoque";
import { join } from "node:path";

import {
  AUTHENTICATED_REPOSITORY_PASSWORD_ENV,
  AUTHENTICATED_REPOSITORY_USERNAME_ENV,
  installAuthenticatedArtifact,
  writeAuthenticatedConsumer,
  writeAuthenticatedLibrary,
} from "./support/authenticated-fixtures.mts";
import { startAuthenticatedFileServer } from "./support/authenticated-server.mts";
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
    await runZolt(t, zolt, ["--no-progress", "build", "--cwd", consumer, "--cache-root", cache]);
    const result = await runZolt(t, zolt, [
      "--no-progress", "run", "--cwd", consumer, "--cache-root", cache, "--", "Codex",
    ]);

    expect.value(result.stdout).toContain("hello Codex from authenticated repo");
    expect.value(server.requests.join("\n")).toContain("internal-greeting-1.0.0.jar");
    await expectTextFile(join(consumer, "zolt.lock"), {
      excludes: [credentials.username, credentials.password],
    });
  });
});
