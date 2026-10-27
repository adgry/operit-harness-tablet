// 白刃破甲 · Operit Harness 内置安装器
//
// 把随 APK 打进 assets 的 bairen 负载落到 DSH_HOME：
//   1. AGENTS.md 人格块（带 BAIREN 标记，幂等）
//   2. skills/bairen-pentest 渗透技能包
//   3. profiles/*/cordis.patch.yml 注册插件 + 提示词/指令预算
//
// 用法：node install-bairen.mjs ASSET_DIR RUNTIME_DIR
// 与桌面版 tools/bairen-dsh/白刃DSH注入.py 的行为保持一致，重复执行不会叠加。
import { readFile, writeFile, mkdir, rm, cp, readdir } from 'node:fs/promises';
import { join, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

const PLUGIN = 'bairen-persona';
const YAML_BEGIN = '# BEGIN BAIREN-DSH persona';
const YAML_END = '# END BAIREN-DSH persona';
const AGENTS_BEGIN = '<!-- BAIREN-DSH:START -->';
const AGENTS_END = '<!-- BAIREN-DSH:END -->';

const PROFILE_CONFIG = [
  YAML_BEGIN,
  '- id: system-prompt',
  '  config:',
  '    includeHarnessIdentity: false',
  '    personaPrefix: ""',
  '    personaSuffix: ""',
  '    includeRuntimeContext: true',
  '- id: agent-instructions',
  '  config:',
  '    maxBytes: 262144',
  '',
].join('\n');

const PROFILE_INSERT = [
  '- insert:',
  `    - id: ${PLUGIN}`,
  `      name: ${PLUGIN}`,
  '      config:',
  '        enabled: true',
  '',
].join('\n');

async function readOrEmpty(path) {
  try {
    return await readFile(path, 'utf8');
  } catch (error) {
    if (error.code === 'ENOENT') return '';
    throw error;
  }
}

function escape(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function blockPattern(begin, end) {
  return new RegExp(`${escape(begin)}[\\s\\S]*?${escape(end)}\\s*`);
}

async function installAgents(dshHome, block) {
  const target = join(dshHome, 'AGENTS.md');
  const existing = await readOrEmpty(target);
  const pattern = blockPattern(AGENTS_BEGIN, AGENTS_END);
  const body = `${block.endsWith('\n') ? block : `${block}\n`}`;
  const next = pattern.test(existing)
    ? existing.replace(pattern, `${body}\n`)
    : `${existing.replace(/\s*$/, '')}${existing.trim() ? '\n\n' : ''}${body}\n`;
  if (next !== existing) await writeFile(target, next, 'utf8');
  return target;
}

async function installSkills(assetDir, dshHome) {
  const source = join(assetDir, 'bairen', 'skills', 'bairen-pentest');
  const target = join(dshHome, 'skills', 'bairen-pentest');
  await rm(target, { recursive: true, force: true });
  await mkdir(join(dshHome, 'skills'), { recursive: true });
  await cp(source, target, { recursive: true });
  return target;
}

async function profileDirs(dshHome) {
  const root = join(dshHome, 'profiles');
  let entries = [];
  try {
    entries = await readdir(root, { withFileTypes: true });
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
  const found = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const name = entry.name.toLowerCase();
    if (name.startsWith('_') || name.includes('backup') || name.includes('bak')) continue;
    const dir = join(root, entry.name);
    const patch = await readOrEmpty(join(dir, 'cordis.patch.yml'));
    const manifest = await readOrEmpty(join(dir, 'package.json'));
    if (patch.trim() || manifest.trim()) found.push(dir);
  }
  return found.sort();
}

async function patchProfiles(dshHome) {
  const patched = [];
  for (const dir of await profileDirs(dshHome)) {
    const target = join(dir, 'cordis.patch.yml');
    const existing = await readOrEmpty(target);
    const pattern = blockPattern(YAML_BEGIN, YAML_END);
    const remaining = existing.replace(pattern, '');
    // 插件管理器可能已经自行写入 insert 条目；此时只补配置行，避免重复插入。
    const registered = new RegExp(`(^|\\n)\\s*-\\s*id:\\s*${PLUGIN}\\b`).test(remaining);
    const block = `${PROFILE_CONFIG}${registered ? '' : PROFILE_INSERT}${YAML_END}\n`;
    const next = pattern.test(existing)
      ? existing.replace(pattern, block)
      : `${existing.replace(/\s*$/, '')}${existing.trim() ? '\n\n' : ''}${block}`;
    if (next !== existing) await writeFile(target, next, 'utf8');
    patched.push(target);
  }
  return patched;
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    const [assetDir, runtimeDir] = process.argv.slice(2);
    if (!assetDir || !runtimeDir) throw new Error('Usage: install-bairen.mjs ASSET_DIR RUNTIME_DIR');
    if (!isAbsolute(assetDir) || !isAbsolute(runtimeDir)) throw new Error('Absolute asset and runtime paths are required');
    const dshHome = join(runtimeDir, 'data');
    await mkdir(dshHome, { recursive: true });
    const agents = await readOrEmpty(join(assetDir, 'bairen', 'agents_block.md'));
    if (!agents.trim()) throw new Error('Missing bundled persona block');
    console.log('operit-harness: phase=bairen');
    await installAgents(dshHome, agents.trim());
    await installSkills(assetDir, dshHome);
    const profiles = await patchProfiles(dshHome);
    console.log(`operit-harness: bairen persona installed (${profiles.length} profile patches)`);
  } catch (error) {
    console.error(`operit-harness: ${error.message}`);
    process.exitCode = 1;
  }
}
