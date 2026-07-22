import type { SmokeContext } from "smoque";
import { createServer, type Server } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { resolve, sep } from "node:path";

export interface BasicCredentials {
  readonly password: string;
  readonly username: string;
}

export interface AuthenticatedFileServer {
  readonly requests: readonly string[];
  url(path?: string): string;
}

export async function startAuthenticatedFileServer(
  t: SmokeContext,
  root: string,
  credentials: BasicCredentials,
): Promise<AuthenticatedFileServer> {
  const repositoryRoot = resolve(root);
  const authorization = `Basic ${Buffer.from(`${credentials.username}:${credentials.password}`).toString("base64")}`;
  const requests: string[] = [];
  const server = createServer(async (request, response) => {
    const method = request.method ?? "GET";
    const pathname = decodeURIComponent(new URL(request.url ?? "/", "http://localhost").pathname);
    requests.push(`${method} ${pathname}`);

    if (request.headers.authorization !== authorization) {
      response.writeHead(401, { "WWW-Authenticate": 'Basic realm="zolt-smoke"' });
      response.end();
      return;
    }
    if (method !== "GET" && method !== "HEAD") {
      response.writeHead(405);
      response.end();
      return;
    }

    const file = resolve(repositoryRoot, `.${pathname}`);
    if (file !== repositoryRoot && !file.startsWith(`${repositoryRoot}${sep}`)) {
      response.writeHead(404);
      response.end();
      return;
    }
    try {
      if (!(await stat(file)).isFile()) {
        throw new Error("not a file");
      }
      const content = await readFile(file);
      response.writeHead(200, { "Content-Length": content.length });
      response.end(method === "HEAD" ? undefined : content);
    } catch {
      response.writeHead(404);
      response.end();
    }
  });

  await listen(server);
  t.cleanup(async () => await close(server));
  const address = server.address();
  if (address === null || typeof address === "string") {
    t.fail("Authenticated repository server did not bind a TCP port.");
  }

  return {
    requests,
    url: (path = "") => `http://127.0.0.1:${address.port}/${path.replace(/^\/+/, "")}`,
  };
}

async function listen(server: Server): Promise<void> {
  await new Promise<void>((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolveListen();
    });
  });
}

async function close(server: Server): Promise<void> {
  if (!server.listening) {
    return;
  }
  await new Promise<void>((resolveClose, reject) => {
    server.close((error) => error === undefined ? resolveClose() : reject(error));
  });
}
