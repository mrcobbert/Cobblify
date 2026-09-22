// The deploy and probe scripts are the only thing that tells the owner whether a
// deployment actually works, so they have to be right about two things the owner cannot
// see: which wrangler config was deployed (a plain `wrangler deploy` silently strips the
// owner account's KV/R2/D1 bindings) and whether the probe authenticated (a token-gated
// deployment answers 401, which used to read as "INCONCLUSIVE - inspect JSON above").
//
// These cases run the real scripts against a local listener that requires the token, and
// against a fake `npx` that records its argv. They bind a socket, so they need an
// environment that allows listen().
import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const TOKEN = "tok_0123456789abcdef";
const workerDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const RUN_PROBE = path.join(workerDir, "scripts", "run-worker-probe.mjs");
const RATE_PROBE = path.join(workerDir, "scripts", "rate-probe.mjs");
const DEPLOY = path.join(workerDir, "scripts", "deploy-and-test.sh");

/** A stand-in for a token-gated deployment: every route answers 401 without the header. */
function startGatedWorker() {
  const seen = [];
  const server = http.createServer((req, res) => {
    const token = req.headers["x-bedwarsqol-token"] ?? null;
    seen.push({ url: req.url, token });
    res.setHeader("content-type", "application/json");
    if (token !== TOKEN) {
      res.writeHead(401);
      res.end(JSON.stringify({ success: false, error: "unauthorized" }));
      return;
    }
    res.writeHead(200);
    res.end(JSON.stringify(req.url.startsWith("/test/") ? { verdict: "PASS" } : { state: "OK" }));
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      resolve({ server, seen, base: `http://127.0.0.1:${server.address().port}` });
    });
  });
}

const close = (server) => new Promise((resolve) => server.close(resolve));

/**
 * spawnSync would deadlock here: the listener above lives in THIS process, and a
 * synchronous wait blocks the event loop that has to answer the child's request.
 */
function runScript(command, args, options) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { ...options, timeout: 30_000, killSignal: "SIGKILL" });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (c) => { stdout += c; });
    child.stderr.on("data", (c) => { stderr += c; });
    child.on("error", reject);
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}

test("the Worker probe authenticates with WORKER_TOKEN", async () => {
  const { server, base } = await startGatedWorker();
  try {
    const run = await runScript(process.execPath, [RUN_PROBE], {
      env: { ...process.env, WORKER_URL: base, WORKER_TOKEN: TOKEN, PLAYER: "beepor" },
    });
    assert.equal(run.status, 0, run.stdout + run.stderr);
    assert.match(run.stdout, /PASS DEFINITIVE/);
  } finally {
    await close(server);
  }
});

test("a 401 names the variable that would have fixed it", async () => {
  const { server, base } = await startGatedWorker();
  try {
    const env = { ...process.env, WORKER_URL: base, PLAYER: "beepor" };
    delete env.WORKER_TOKEN;
    const run = await runScript(process.execPath, [RUN_PROBE], { env });
    assert.equal(run.status, 1);
    assert.match(run.stdout + run.stderr, /WORKER_TOKEN/);
  } finally {
    await close(server);
  }
});

test("the rate probe sends the token on every request", async () => {
  const { server, seen, base } = await startGatedWorker();
  try {
    const run = await runScript(process.execPath, [RATE_PROBE], {
      env: { ...process.env, WORKER_URL: base, WORKER_TOKEN: TOKEN, RATE_PROBE_N: "1" },
    });
    assert.equal(run.status, 0, run.stdout + run.stderr);
    const hits = seen.filter((r) => r.url.startsWith("/bedwars/"));
    assert.ok(hits.length > 0, "the probe made no /bedwars request");
    assert.deepEqual([...new Set(hits.map((r) => r.token))], [TOKEN]);
  } finally {
    await close(server);
  }
});

test("deploy-and-test.sh deploys the config it was given", async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), "cobblify-deploy-"));
  try {
    const log = path.join(dir, "npx.log");
    await writeFile(
      path.join(dir, "npx"),
      '#!/usr/bin/env bash\nprintf "%s " "$@" >> "$NPX_LOG"\nprintf "\\n" >> "$NPX_LOG"\n' +
        'if [ "$1" = "wrangler" ] && [ "$2" = "deploy" ]; then\n' +
        '  echo "Uploaded bedwarsqol-stats"\n  echo "https://bedwarsqol-stats.test.workers.dev"\nfi\nexit 0\n',
    );
    await writeFile(path.join(dir, "npm"), "#!/usr/bin/env bash\nexit 0\n");
    await chmod(path.join(dir, "npx"), 0o755);
    await chmod(path.join(dir, "npm"), 0o755);

    const run = await runScript("bash", [DEPLOY], {
      cwd: workerDir,
      env: {
        ...process.env,
        PATH: `${dir}:${process.env.PATH}`,
        NPX_LOG: log,
        CLOUDFLARE_API_TOKEN: "x",
        WRANGLER_CONFIG: "wrangler.owner.toml",
      },
    });
    assert.equal(run.status, 0, run.stdout + run.stderr);
    const argv = await readFile(log, "utf8");
    assert.match(argv, /wrangler deploy -c wrangler\.owner\.toml/);
    assert.match(run.stdout, /wrangler\.owner\.toml/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
