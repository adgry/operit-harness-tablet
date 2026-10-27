import { mkdir, readFile, writeFile, cp, rm } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve, join } from 'node:path';
import { verifyArtifact } from './install-profile.mjs';
const root = fileURLToPath(new URL('../', import.meta.url));
const lockPath = join(root, 'upstream-lock.json');
const lock = JSON.parse(await readFile(lockPath, 'utf8'));
await mkdir(join(root, 'packages'), { recursive: true });
// Local adapters ship as ordinary plugin archives; their hashes join the same lock.
for (const name of ['operit-android-bridge', 'operit-tablet-ui', 'bairen-persona']) {
  const dir = join(root, 'plugins', name);
  const manifest = JSON.parse(await readFile(join(dir, 'package.json'), 'utf8'));
  const packed = spawnSync('npm', ['pack', '--ignore-scripts', '--json', '--pack-destination', join(root, 'packages')], {cwd:dir,encoding:'utf8'});
  if (packed.status !== 0) throw new Error(`Cannot package ${name}: ${packed.stderr}`);
  const receipt = JSON.parse(packed.stdout)[0];
  const sha256 = createHash('sha256').update(await readFile(join(root, 'packages', receipt.filename))).digest('hex');
  const item = {name,version:manifest.version,role:'plugin',file:receipt.filename,source:`plugins/${name}`,sha256};
  const index = lock.packages.findIndex(p => p.name === name);
  if (index < 0) lock.packages.push(item); else lock.packages[index] = item;
}
for (const p of lock.packages) await verifyArtifact(join(root, 'packages', p.file), p.sha256);
lock.engineLockSha256 = createHash('sha256').update(await readFile(join(root,'engine','package-lock.json'))).digest('hex');
lock.engineManifestSha256 = createHash('sha256').update(await readFile(join(root,'engine','package.json'))).digest('hex');
await writeFile(lockPath, JSON.stringify(lock, null, 2) + '\n');
const version = lock.packages.find(p => p.name === '@deepseek-ai/dsh').version;
for (const value of [lock.node.version, version]) if (!/^[0-9a-z.-]+$/.test(value)) throw new Error('Invalid pinned version');
for (const value of [lock.node.linuxArm64Sha256, lock.node.linuxX64Sha256]) if (!/^[a-f0-9]{64}$/.test(value)) throw new Error('Invalid Node digest');
const dest = resolve(root, '../app/src/main/assets/harness');
await rm(dest, {recursive:true,force:true});
await mkdir(dest, {recursive:true});
for (const path of ['bootstrap.sh','scripts','bairen','upstream-lock.json']) await cp(join(root,path),join(dest,path),{recursive:true});
await mkdir(join(dest,'packages'), {recursive:true});
for (const p of lock.packages) await cp(join(root,'packages',p.file),join(dest,'packages',p.file));
await mkdir(join(dest,'engine'), {recursive:true});
for (const path of ['package.json','package-lock.json']) await cp(join(root,'engine',path),join(dest,'engine',path));
await writeFile(join(dest,'node.env'), `NODE_VERSION='${lock.node.version}'\nDSH_VERSION='${version}'\nNODE_SHA_ARM64='${lock.node.linuxArm64Sha256}'\nNODE_SHA_X64='${lock.node.linuxX64Sha256}'\n`);
console.log(`Synced ${lock.packages.length} verified archives to app assets/harness`);
