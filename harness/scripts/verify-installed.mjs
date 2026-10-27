import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { verifyFullProfile } from './install-profile.mjs';
const [assets, runtime] = process.argv.slice(2);
try {
  const lock = JSON.parse(await readFile(join(assets, 'upstream-lock.json'), 'utf8'));
  const profile = join(runtime, 'data', 'profiles', 'web');
  verifyFullProfile(JSON.parse(await readFile(join(profile, 'package.json'), 'utf8')));
  for (const p of lock.packages) {
    const base = p.role === 'engine' ? join(runtime, `engine-${lock.packages.find(p => p.name === '@deepseek-ai/dsh').version}`) : profile;
    const installed = JSON.parse(await readFile(join(base, 'node_modules', p.name, 'package.json'), 'utf8'));
    if (installed.version !== p.version) throw new Error(`Installed version differs: ${p.name}`);
  }
} catch (error) { console.error(`operit-harness: ${error.message}`); process.exitCode = 1; }
