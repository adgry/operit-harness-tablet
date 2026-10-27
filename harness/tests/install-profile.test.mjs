import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { verifyArtifact, verifyFullProfile, validateBridge, installProfile } from '../scripts/install-profile.mjs';

test('a modified upstream archive is rejected before any package-manager mutation', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'operit-harness-test-'));
  try {
    await writeFile(join(dir, 'plugin.tgz'), 'modified');
    let invoked = false;
    await assert.rejects(installProfile({
      dshHome: join(dir, 'data'), command: '/locked/dsh', artifactDir: dir,
      packages: [{ file: 'plugin.tgz', sha256: '0'.repeat(64) }],
      run: async () => { invoked = true; },
    }), /SHA256 mismatch/);
    assert.equal(invoked, false);
  } finally { await rm(dir, { recursive: true }); }
});

test('official complete web/base composition is required; minimal profiles are rejected', () => {
  assert.throws(() => verifyFullProfile({ dsh: { profile: { bundles: ['@deepseek-ai/dsh-sdk-minimal'] } } }), /full web\/base/);
  assert.doesNotThrow(() => verifyFullProfile({ dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app', 'dsh-claude-style'] } } }));
});

test('native credentials can only target the exact authenticated loopback MCP endpoint', () => {
  for (const url of ['https://example.com/mcp', 'http://localhost:3088/mcp', 'http://127.0.0.1.evil/mcp', 'http://127.0.0.1:3088/mcp?token=x', 'http://user@127.0.0.1:3088/mcp']) {
    assert.throws(() => validateBridge(url, 'x'.repeat(43)), /loopback/);
  }
  assert.throws(() => validateBridge('http://127.0.0.1:3088/mcp', ''), /token/);
  assert.doesNotThrow(() => validateBridge('http://127.0.0.1:3088/mcp', 'x'.repeat(43)));
});

test('profile updates preserve the user patch and private data and use verified local archives', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'operit-harness-test-'));
  try {
    const profile = join(dir, 'data', 'profiles', 'web');
    await mkdir(profile, { recursive: true });
    await writeFile(join(profile, 'cordis.patch.yml'), '- id: user-preference\n  config: {}\n');
    await writeFile(join(profile, 'private-data'), 'keep');
    await writeFile(join(profile, 'package.json'), JSON.stringify({ dsh: { profile: { bundles: ['@deepseek-ai/dsh-base', '@deepseek-ai/dsh-web-app'] } } }));
    await writeFile(join(dir, 'original.tgz'), 'abc');
    const calls = [];
    await installProfile({ dshHome: join(dir, 'data'), command: '/locked/dsh', artifactDir: dir,
      packages: [{file:'original.tgz',sha256:'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'}],
      run: async (command,args,env) => { calls.push({command,args,home:env.DSH_HOME}); },
    });
    assert.deepEqual(calls.map(c => c.args), [
      ['plugin','--profile','web','list','--depth','0'],
      ['plugin','--profile','web','add',join(dir,'original.tgz')],
    ]);
    assert.ok(calls.every(c => c.home === join(dir,'data')));
    assert.equal(await readFile(join(profile,'private-data'),'utf8'),'keep');
    assert.equal(await readFile(join(profile,'cordis.patch.yml'),'utf8'),'- id: user-preference\n  config: {}\n');
    await verifyArtifact(join(dir,'original.tgz'),'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
  } finally { await rm(dir, {recursive:true}); }
});
