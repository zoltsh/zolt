import { constants } from "node:fs";
import { access } from "node:fs/promises";

import type { PostgresDatabase, SmokeContext } from "smoque";

const PSQL_CANDIDATES = [
  "/opt/homebrew/opt/libpq/bin/psql",
  "/usr/local/opt/libpq/bin/psql",
] as const;

export interface PostgresRuntime {
  readonly database: PostgresDatabase;
  readonly env: Readonly<Record<string, string>>;
  readonly table: string;
}

export interface EphemeralPostgresOptions {
  readonly database: string;
  readonly image?: string;
  readonly password: string;
  readonly table: string;
  readonly user: string;
}

export async function postgresClientCommand(): Promise<string> {
  const configured = process.env.ZOLT_SMOKE_PSQL?.trim();
  if (configured) {
    return configured;
  }
  for (const candidate of PSQL_CANDIDATES) {
    try {
      await access(candidate, constants.X_OK);
      return candidate;
    } catch {
      // Continue to the next well-known client location.
    }
  }
  return "psql";
}

export function postgresRuntime(database: PostgresDatabase, table: string): PostgresRuntime {
  const url = new URL(database.url);
  return {
    database,
    table,
    env: {
      PGHOST: url.hostname,
      PGPORT: url.port,
      PGDATABASE: decodeURIComponent(url.pathname.slice(1)),
      PGUSER: decodeURIComponent(url.username),
      PGPASSWORD: decodeURIComponent(url.password),
      PGNOTES_TABLE: table,
      PGCONNECT_TIMEOUT_MS: "5000",
      PGPOOL_CONNECTION_TIMEOUT_MS: "5000",
    },
  };
}

export async function startEphemeralPostgres(
  t: SmokeContext,
  options: EphemeralPostgresOptions,
): Promise<PostgresRuntime> {
  const root = await t.tempDir("postgres-tmpfs-compose");
  const composeFile = root.path("compose.yaml");
  await t.fs.writeText(composeFile, [
    "services:",
    "  postgres:",
    `    image: ${options.image ?? "postgres:16-alpine"}`,
    "    environment:",
    `      POSTGRES_DB: ${options.database}`,
    `      POSTGRES_USER: ${options.user}`,
    `      POSTGRES_PASSWORD: ${options.password}`,
    "    ports:",
    '      - "127.0.0.1::5432"',
    "    tmpfs:",
    "      - /var/lib/postgresql/data",
    "",
  ].join("\n"));
  const stack = await t.compose.up({ file: composeFile, services: ["postgres"] });
  const published = await stack.service("postgres").port(5432);
  const url = `postgres://${encodeURIComponent(options.user)}:${encodeURIComponent(options.password)}`
    + `@${published.host}:${published.port}/${encodeURIComponent(options.database)}`;
  const database = await t.postgres.connect({
    url,
    psql: await postgresClientCommand(),
    timeout: "30s",
  });
  await t.poll("PostgreSQL tmpfs readiness", async () => {
    await database.query("select 1 as ok");
  }, { timeout: "1m", interval: "250ms" });
  return postgresRuntime(database, options.table);
}
