import { spawnSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const [packageName, binPath, ...args] = process.argv.slice(2);

if (!packageName || !binPath) {
  console.error(
    "Usage: node ./scripts/run-pnpm-package-bin.mjs <package-name> <bin-path> [args...]"
  );
  process.exit(1);
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const projectDir = resolve(scriptDir, "..");
const pnpmStoreDir = join(projectDir, "node_modules", ".pnpm");
const storePrefix = `${packageName.replace("/", "+")}@`;

const matchingStoreDirs = readdirSync(pnpmStoreDir, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && entry.name.startsWith(storePrefix))
  .map((entry) => entry.name)
  .sort();

if (matchingStoreDirs.length === 0) {
  console.error(`Unable to find ${packageName} in ${pnpmStoreDir}.`);
  process.exit(1);
}

const storeDir = join(pnpmStoreDir, matchingStoreDirs.at(-1));
const packageDir = join(storeDir, "node_modules", ...packageName.split("/"));
const binFile = join(packageDir, binPath);

if (!existsSync(binFile)) {
  console.error(`Unable to find ${binPath} for ${packageName} at ${binFile}.`);
  process.exit(1);
}

const separator = process.platform === "win32" ? ";" : ":";
const nodePathEntries = [
  join(packageDir, "bin", "node_modules"),
  join(packageDir, "node_modules"),
  join(storeDir, "node_modules"),
  join(projectDir, "node_modules", ".pnpm", "node_modules"),
  join(projectDir, "node_modules"),
]
  .filter((entry) => existsSync(entry))
  .filter((entry, index, entries) => entries.indexOf(entry) === index)
  .join(separator);

const child = spawnSync(process.execPath, [binFile, ...args], {
  cwd: projectDir,
  env: {
    ...process.env,
    NODE_PATH: process.env.NODE_PATH
      ? `${nodePathEntries}${separator}${process.env.NODE_PATH}`
      : nodePathEntries,
  },
  stdio: "inherit",
});

if (typeof child.status === "number") {
  process.exit(child.status);
}

process.exit(1);
