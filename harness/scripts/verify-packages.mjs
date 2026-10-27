import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { verifyArtifact } from './install-profile.mjs';
try {
  const root = process.argv[2];
  const lock = JSON.parse(await readFile(join(root, 'upstream-lock.json'), 'utf8'));
  for (const p of lock.packages) await verifyArtifact(join(root, 'packages', p.file), p.sha256);
  await verifyArtifact(join(root,'engine','package-lock.json'), lock.engineLockSha256);
  await verifyArtifact(join(root,'engine','package.json'), lock.engineManifestSha256);
} catch (error) { console.error(`operit-harness: ${error.message}`); process.exitCode = 1; }
