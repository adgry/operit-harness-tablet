import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync, existsSync } from 'node:fs';
import vm from 'node:vm';

// The shell and browser are external boundaries. These small doubles expose only
// the official services used by this plugin; rendered layout is tested in Tabbit.
function load(userAgent) {
  const styles = [];
  const sections = [];
  const cleanups = [];
  const document = { createElement: () => ({ setAttribute() {}, remove() { styles.splice(styles.indexOf(this), 1); } }), head: { appendChild: node => styles.push(node) } };
  let plugin = {};
  const window = { __ModuleLoader__: { load: ({ factory }) => { plugin = factory(() => ({ createElement: (type, props, ...children) => ({ type, props, children }) })); } } };
  const source = new URL('../client.js', import.meta.url);
  if (existsSync(source)) vm.runInNewContext(readFileSync(source, 'utf8'), { window, document, navigator: { userAgent } });
  const ctx = new Proxy({
    effect: setup => { const cleanup = setup(); if (cleanup) cleanups.push(cleanup); },
    slots: { inject: (_name, setup) => { const cleanup = setup(); let active = true; const stop = () => { if (!active) return; active = false; cleanup?.(); }; cleanups.push(stop); return stop; }, register: (metadata, render) => { const section = { metadata, render }; sections.push(section); return () => sections.splice(sections.indexOf(section), 1); } },
  }, { get(target, key) {
    if (key === 'slots' && !plugin.inject?.includes('slots')) throw new Error('slots service is not declared in plugin.inject');
    return target[key];
  } });
  return { plugin, ctx, styles, sections, cleanups };
}

test('applying adapter owns exactly one stylesheet and disposal removes it', () => {
  const env = load('Desktop');
  env.plugin.apply?.(env.ctx);
  assert.equal(env.styles.length, 1, 'adapter style must be installed');
  assert.equal(typeof env.plugin.dispose, 'function');
  env.plugin.dispose(env.ctx);
  env.plugin.dispose(env.ctx);
  assert.equal(env.styles.length, 0, 'dispose is idempotent');
});

test('ordinary desktop and lookalike markers never expose native entry', () => {
  for (const ua of ['Desktop Chrome/153', 'Desktop OperitHarness/0.10', 'Desktop OtherOperitHarness/0.1']) {
    const env = load(ua); env.plugin.apply?.(env.ctx);
    assert.equal(env.sections.length, 0);
  }
});

test('exact Operit host marker registers real device link without status claims', () => {
  const env = load('Mozilla/5.0 OperitHarness/0.1');
  env.plugin.apply?.(env.ctx);
  assert.equal(env.sections.length, 1, 'native settings entry required');
  const tree = env.sections[0].render();
  const nodes = [];
  const visit = node => { if (!node || typeof node !== 'object') return; nodes.push(node); node.children?.flat(Infinity).forEach(visit); };
  visit(tree);
  assert.equal(nodes.find(n => n.type === 'a')?.props.href, 'operit-harness://device');
  env.cleanups.forEach(fn => fn());
  assert.equal(env.styles.length, 0);
  assert.equal(env.sections.length, 0);
});

test('explicit disposal and reapply do not leave native settings registrations', () => {
  const env = load('OperitHarness/0.1');
  env.plugin.apply(env.ctx);
  env.plugin.apply(env.ctx);
  assert.equal(env.sections.length, 1);
  env.plugin.dispose(env.ctx);
  assert.equal(env.sections.length, 0);
  assert.equal(env.styles.length, 0);
});
