import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { isAbsolute, join, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

export async function verifyArtifact(path, expected) {
  if (!/^[a-f0-9]{64}$/.test(expected)) throw new Error('Invalid artifact SHA256');
  const actual = createHash('sha256').update(await readFile(path)).digest('hex');
  if (actual !== expected) throw new Error(`SHA256 mismatch: ${basename(path)}`);
}

export function verifyFullProfile(manifest) {
  const bundles = manifest.dsh?.profile?.bundles;
  if (!Array.isArray(bundles) || !bundles.includes('@deepseek-ai/dsh-base') ||
      !bundles.includes('@deepseek-ai/dsh-web-app') || bundles.includes('@deepseek-ai/dsh-sdk-minimal')) {
    throw new Error('The full web/base Harness profile is required');
  }
}

export function validateBridge(address, token) {
  const url = new URL(address);
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port ||
      url.pathname !== '/mcp' || url.search || url.hash || url.username || url.password) {
    throw new Error('Bridge requires the exact loopback MCP endpoint');
  }
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw new Error('Invalid bridge token');
}

async function runCommand(command, args, env) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { env, stdio: 'inherit' });
    child.once('error', reject);
    child.once('exit', (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`Harness plugin installation failed (${signal ?? code})`));
    });
  });
}

export async function installProfile({ dshHome, command, artifactDir, packages, run = runCommand }) {
  if (!isAbsolute(dshHome) || !isAbsolute(artifactDir) || !isAbsolute(command)) {
    throw new Error('Explicit absolute runtime/profile paths are required');
  }
  // Validate the complete set before allowing the package manager to mutate a profile.
  for (const item of packages) {
    if (basename(item.file) !== item.file || !item.file.endsWith('.tgz')) throw new Error('Only local package archives are allowed');
    await verifyArtifact(join(artifactDir, item.file), item.sha256);
  }
  const env = { ...process.env, DSH_HOME: dshHome };
  await run(command, ['plugin', '--profile', 'web', 'list', '--depth', '0'], env);
  verifyFullProfile(JSON.parse(await readFile(join(dshHome, 'profiles', 'web', 'package.json'), 'utf8')));
  await run(command, ['plugin', '--profile', 'web', 'add', ...packages.map(p => join(artifactDir, p.file))], env);
  verifyFullProfile(JSON.parse(await readFile(join(dshHome, 'profiles', 'web', 'package.json'), 'utf8')));
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    const [assetDir, engineDir, runtimeDir] = process.argv.slice(2);
    if (!assetDir || !engineDir || !runtimeDir) throw new Error('Usage: install-profile.mjs ASSET_DIR ENGINE_DIR RUNTIME_DIR');
    validateBridge(process.env.OPERIT_BRIDGE_URL, process.env.OPERIT_BRIDGE_TOKEN);
    const lock = JSON.parse(await readFile(join(assetDir, 'upstream-lock.json'), 'utf8'));
    await installProfile({
      dshHome: join(runtimeDir, 'data'), command: join(engineDir, 'node_modules', '.bin', 'dsh'),
      artifactDir: join(assetDir, 'packages'), packages: lock.packages.filter(p => p.role === 'plugin'),
    });
  } catch (error) {
    console.error(`operit-harness: ${error.message}`);
    process.exitCode = 1;
  }
}
