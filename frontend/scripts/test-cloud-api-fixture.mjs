import { spawnSync } from "node:child_process";
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = resolve(fileURLToPath(new URL("..", import.meta.url)));
const fixturePath = join(
  frontendRoot,
  "openapi/fixtures/cloud-generator-v1.yaml",
);
const generator = join(frontendRoot, "scripts/generate-cloud-api.mjs");

const sandbox = mkdtempSync(join(tmpdir(), "cloud-api-fixture-"));
const changedFixtureDirectory = mkdtempSync(
  join(frontendRoot, ".cloud-openapi-fixture-"),
);
const target = join(changedFixtureDirectory, "generated-client");
const snapshot = join(sandbox, "first-run");
const env = {
  ...process.env,
  CLOUD_OPENAPI_SPEC: fixturePath,
  CLOUD_OPENAPI_OUTPUT: relative(frontendRoot, target),
};

const run = (args, expectedStatus = 0, expectedOutput) => {
  const result = spawnSync(process.execPath, [generator, ...args], {
    cwd: frontendRoot,
    env,
    encoding: "utf8",
  });
  if (result.error) throw result.error;
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  if (result.status !== expectedStatus) {
    throw new Error(
      `Expected exit code ${expectedStatus}, got ${result.status}`,
    );
  }
  const output = `${result.stdout ?? ""}${result.stderr ?? ""}`;
  if (expectedOutput && !output.includes(expectedOutput)) {
    throw new Error(`Expected command output to include: ${expectedOutput}`);
  }
};

const files = (directory) => {
  const found = [];
  const visit = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile()) found.push(path.slice(directory.length + 1));
    }
  };
  visit(directory);
  return found.sort();
};

const combinedText = (directory) =>
  files(directory)
    .map((file) => readFileSync(join(directory, file), "utf8"))
    .join("\n");

const sameTree = (left, right) => {
  const leftFiles = files(left);
  const rightFiles = files(right);
  return (
    JSON.stringify(leftFiles) === JSON.stringify(rightFiles) &&
    leftFiles.every((file) =>
      readFileSync(join(left, file)).equals(readFileSync(join(right, file))),
    )
  );
};

try {
  try {
    run([]);
    if (
      !combinedText(target).includes("listWidgets") ||
      !combinedText(target).includes("getWidget")
    ) {
      throw new Error(
        "Initial fixture generation must contain both listWidgets and getWidget.",
      );
    }
    cpSync(target, snapshot, { recursive: true });

    run([]);
    if (!sameTree(snapshot, target)) {
      throw new Error("Repeated fixture generation produced different output.");
    }

    const staleFile = files(target)[0];
    if (!staleFile) {
      throw new Error("Fixture generation produced no files.");
    }
    writeFileSync(join(target, staleFile), "\n// intentionally stale\n", {
      flag: "a",
    });
    run(["--check"], 1, "Generated Cloud client is stale. Changed files:");

    const changedFixture = readFileSync(fixturePath, "utf8").replace(
      /  \/widgets\/\{id\}:\n[\s\S]*?(?=components:)/,
      "",
    );
    const changedPath = join(
      changedFixtureDirectory,
      "cloud-generator-v2.yaml",
    );
    writeFileSync(changedPath, changedFixture);
    env.CLOUD_OPENAPI_SPEC = changedPath;

    mkdirSync(join(target, "api"), { recursive: true });
    writeFileSync(join(target, "obsolete-sentinel.ts"), "// obsolete\n");
    writeFileSync(join(target, "api", "obsolete-nested.ts"), "// obsolete\n");

    run([]);
    if (
      existsSync(join(target, "obsolete-sentinel.ts")) ||
      existsSync(join(target, "api", "obsolete-nested.ts"))
    ) {
      throw new Error("Regeneration retained obsolete sentinel files.");
    }
    const regeneratedText = combinedText(target);
    if (regeneratedText.includes("getWidget")) {
      throw new Error("Regeneration retained the removed getWidget operation.");
    }
    if (!regeneratedText.includes("listWidgets")) {
      throw new Error(
        "Regeneration removed the retained listWidgets operation.",
      );
    }
    run(["--check"]);
  } finally {
    rmSync(target, { recursive: true, force: true });
  }

  console.log("Cloud OpenAPI fixture generation regression test passed.");
} finally {
  rmSync(sandbox, { recursive: true, force: true });
  rmSync(changedFixtureDirectory, { recursive: true, force: true });
}
