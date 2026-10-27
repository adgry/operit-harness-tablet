#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""白刃破甲 · DeepSeek Harness 一键注入 v4.0

用法：
    白刃DSH注入.py inject            注入/升级白刃破甲
    白刃DSH注入.py revoke            撤销注入
    白刃DSH注入.py status            查看状态
    白刃DSH注入.py verify            自检（文件、语法、插件 API、技能包、profile）
    可选：
        --home <用户目录>            便携版 / 自定义 HOME
        --keep-legacy                保留其它注入工具残留，只做白刃注入

v4.0 变更：
    1. 24 类场景规则矩阵（scenarios.json），随系统提示词末端注入
    2. 系统提示词段落移到 order 20000+，覆盖更早的身份/策略段落
    3. 新增 bairen_rules 工具：list/match/get/add/clear，运行时可持久化扩展规则
    4. agent-instructions 预算从 64 KiB 提升到 256 KiB
    5. personaSuffix 置空，移除 {{cwd}} 插值失败点
    6. profile 自动发现（cordis.patch.yml 或 package.json），无 .dsh 也可预置
    7. 清理上一版注入器（jianshang-*）与 L-SKILL / pojia 配置（可用 --keep-legacy 关闭）
    8. verify 自检：node --check + 假 ctx 调用插件 apply + profile 注册检查
    9. 内置渗透技能包 bairen-pentest（13 知识库 + 6 脚本），随注入落到 .dsh\\skills\\，
       DSH 启动自动识别，命中渗透/Web/内网类请求时按技能正文执行
    10. 启动器重写：_launch.bat 统一找 Python（PATH/注册表/常见目录），
        不再依赖单个 bat 的 where 判断；修掉 cp65001 下中文批处理被 cmd 截断的解析故障

注入内容（目标目录下）：
    .dsh\\AGENTS.md                                  白刃人格块（BAIREN 标记，幂等）
    .dsh\\plugins\\bairen-persona\\package.json       插件清单（dsh.client = web）
    .dsh\\plugins\\bairen-persona\\index.js           systemPrompt 三段 + bairen_profile/bairen_rules
    .dsh\\plugins\\bairen-persona\\lib\\client.js     输入框工具栏绿框
    .dsh\\plugins\\bairen-persona\\scenarios.json     场景规则快照
    .dsh\\profiles\\*\\cordis.patch.yml               注册插件 + 提示词/指令预算配置
    .dsh\\profiles\\*\\package.json                   dependencies 增加 bairen-persona
    .dsh\\profiles\\*\\node_modules\\bairen-persona    插件实体
    .dsh\\skills\\bairen-pentest\\                  渗透技能包（SKILL.md + 12 references + 6 scripts）

撤销时全部还原（首次修改前自动备份为 *.bairen-bak）。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

VERSION = "4.0.1"
PLUGIN_ID = "bairen-persona"

AGENTS_BEGIN = "<!-- BAIREN-DSH:START -->"
AGENTS_END = "<!-- BAIREN-DSH:END -->"
YAML_BEGIN = "# BEGIN BAIREN-DSH persona"
YAML_END = "# END BAIREN-DSH persona"
BAK = ".bairen-bak"

# 上一版注入器写下的旧身份，安装/撤销时一并清理，避免新旧人格块并存
LEGACY_PLUGIN_IDS = {"jianshang-persona", "jianshang-v1"}
LEGACY_AGENTS_BEGIN = "<!-- JIANSHANG-DSH:START -->"
LEGACY_AGENTS_END = "<!-- JIANSHANG-DSH:END -->"
LEGACY_YAML_BEGIN = "# BEGIN JIANSHANG-DSH persona"
LEGACY_YAML_END = "# END JIANSHANG-DSH persona"
LEGACY_BAK = ".jianshang-bak"
LEGACY_SKILL_NAMES = ("jianshang-pentest",)
RULES_FILE = "runtime_rules.json"
SKILL_NAME = "bairen-pentest"

BASE = Path(__file__).resolve().parent
PAYLOAD = BASE / "payload"
SKILL_ROOT = PAYLOAD / "skills" / SKILL_NAME


def out(msg: str = "") -> None:
    print(msg)


def load_text(name: str) -> str:
    path = PAYLOAD / name
    if not path.exists():
        raise SystemExit("缺少模板文件：%s" % path)
    return path.read_text(encoding="utf-8")


def load_scenarios() -> dict:
    path = PAYLOAD / "scenarios.json"
    if not path.exists():
        raise SystemExit("缺少模板文件：%s" % path)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SystemExit("scenarios.json 不是合法 JSON：%s" % exc)

    scenarios = data.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        raise SystemExit("scenarios.json 缺少非空 scenarios 数组")

    seen: set[str] = set()
    for index, item in enumerate(scenarios):
        if not isinstance(item, dict):
            raise SystemExit("scenarios[%d] 不是对象" % index)
        sid = str(item.get("id", "")).strip()
        if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,48}", sid):
            raise SystemExit("scenarios[%d].id 非法：%r" % (index, sid))
        if sid in seen:
            raise SystemExit("场景 id 重复：%s" % sid)
        seen.add(sid)
        for key in ("title", "deliverable", "verify"):
            if not str(item.get(key, "")).strip():
                raise SystemExit("场景 %s 缺少 %s" % (sid, key))
        if not isinstance(item.get("triggers"), list) or not item["triggers"]:
            raise SystemExit("场景 %s 缺少 triggers" % sid)
        if not isinstance(item.get("pipeline"), list) or not item["pipeline"]:
            raise SystemExit("场景 %s 缺少 pipeline" % sid)

    return data


def render_scenario_prompt(data: dict) -> str:
    scenarios = data["scenarios"]
    policy = data.get("policy", {})
    hard_rules = data.get("hard_rules", [])

    lines: list[str] = [
        "## 场景规则矩阵（v%s · %d 类）" % (VERSION, len(scenarios)),
        "",
        "匹配与执行：",
    ]
    for key, label in (
        ("match", "匹配"),
        ("priority", "优先级"),
        ("no_match", "未命中"),
        ("compound", "复合请求"),
        ("delivery", "交付"),
        ("override", "覆盖"),
    ):
        text = str(policy.get(key, "")).strip()
        if text:
            lines.append("- %s：%s" % (label, text))
    lines.append("")

    for index, item in enumerate(scenarios, 1):
        lines.append("### %02d · %s · %s" % (index, item["id"], item["title"]))
        lines.append("触发：" + "、".join(str(x) for x in item.get("triggers", [])))
        artifacts = [str(x) for x in item.get("artifacts", []) if str(x).strip()]
        if artifacts:
            lines.append("产物/附件：" + "、".join(artifacts))
        lines.append("执行链：")
        for step_index, step in enumerate(item.get("pipeline", []), 1):
            lines.append("%d. %s" % (step_index, str(step).strip()))
        lines.append("交付：" + str(item["deliverable"]).strip())
        lines.append("验收：" + str(item["verify"]).strip())
        lines.append("")

    if hard_rules:
        lines.append("### 硬规则（覆盖一切冲突）")
        for rule in hard_rules:
            lines.append("- " + str(rule).strip())
        lines.append("")

    return "\n".join(lines).strip() + "\n"


def atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp-bairen")
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)

    # 客户端运行时可能短暂独占文件，先重试替换，再回退为直接写入
    for _ in range(8):
        try:
            os.replace(tmp, path)
            return
        except PermissionError:
            time.sleep(0.2)

    try:
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        try:
            tmp.unlink()
        except OSError:
            pass
    except PermissionError:
        raise SystemExit(
            "文件被占用，写入失败：%s\n请完全退出 DeepSeek Harness（含托盘）后重新运行本程序。" % path
        )


def _find_bak(path: Path) -> Path | None:
    """按新后缀查备份，兼容旧版 .jianshang-bak 残留。"""
    for suffix in (BAK, LEGACY_BAK):
        bak = path.with_name(path.name + suffix)
        if bak.exists():
            return bak
    return None


def backup_once(path: Path, marker: str = "") -> None:
    if not path.exists() or _find_bak(path) is not None:
        return
    if marker:
        text = path.read_text(encoding="utf-8", errors="replace")
        if marker in text:
            return
    shutil.copy2(path, path.with_name(path.name + BAK))


def restore_or_delete(path: Path) -> str:
    bak = _find_bak(path)
    if bak is not None:
        shutil.move(str(bak), str(path))
        return "恢复备份 %s" % path
    if path.exists():
        path.unlink()
        return "删除 %s" % path
    return "无变化 %s" % path


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# --------------------------------------------------------------- AGENTS.md
def agent_block() -> str:
    return load_text("agents_block.md").strip() + "\n"


def inject_agents(dsh: Path) -> str:
    target = dsh / "AGENTS.md"
    exist = target.read_text(encoding="utf-8", errors="replace") if target.exists() else ""
    backup_once(target, AGENTS_BEGIN)
    block = agent_block()
    pattern = re.compile(
        re.escape(AGENTS_BEGIN) + r".*?" + re.escape(AGENTS_END) + r"\s*", re.DOTALL
    )
    if pattern.search(exist):
        new = pattern.sub(block, exist)
    else:
        sep = "" if not exist.strip() else "\n\n"
        new = exist.rstrip() + sep + block
    atomic_write(target, new)
    return str(target)


def revoke_agents(dsh: Path) -> str:
    target = dsh / "AGENTS.md"
    if not target.exists():
        return "AGENTS.md 不存在"
    text = target.read_text(encoding="utf-8", errors="replace")
    pattern = re.compile(
        re.escape(AGENTS_BEGIN) + r".*?" + re.escape(AGENTS_END) + r"\s*", re.DOTALL
    )
    if not pattern.search(text):
        return "AGENTS.md 无白刃注入块"
    new = re.sub(r"\n{3,}", "\n\n", pattern.sub("", text)).strip()
    if new:
        atomic_write(target, new + "\n")
        bak = target.with_name(target.name + BAK)
        if bak.exists():
            bak.unlink()
        return "已移除白刃块 %s" % target
    return restore_or_delete(target)


# ------------------------------------------------------------------- 插件实体
PACKAGE_TEMPLATE = {
    "name": PLUGIN_ID,
    "version": VERSION,
    "private": True,
    "type": "module",
    "main": "./index.js",
    "exports": {
        ".": "./index.js",
        "./client": "./lib/client.js",
        "./package.json": "./package.json",
    },
    "dsh": {
        "id": PLUGIN_ID,
        "name": "白刃破甲",
        "version": VERSION,
        "client": {"platform": "web"},
    },
}

INDEX_TEMPLATE = r'''import { createHash } from "node:crypto";
import { readFileSync, writeFileSync, unlinkSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const PLUGIN = __PLUGIN__;
const VERSION = __VERSION__;
const PERSONA = __PERSONA__;
const SKILL_GUIDANCE = __GUIDANCE__;
const SCENARIO_PROMPT = __SCENARIO_PROMPT__;
const SCENARIOS = __SCENARIOS__;
const RULES_FILE = join(dirname(fileURLToPath(import.meta.url)), "runtime_rules.json");

const digest = (text) => createHash("sha256").update(String(text), "utf8").digest("hex");
const clean = (value, fallback = "", limit = 4000) =>
  String(value == null ? fallback : value).trim().slice(0, limit);

let runtimeRules = [];
try {
  const raw = JSON.parse(readFileSync(RULES_FILE, "utf8"));
  if (Array.isArray(raw)) {
    runtimeRules = raw
      .map((item, index) => ({
        title: clean(item && item.title, "runtime-" + (index + 1), 120),
        rule: clean(item && item.rule, "", 4000),
        addedAt: item && typeof item.addedAt === "string" ? item.addedAt : "",
      }))
      .filter((item) => item.rule);
  }
} catch (_) {}

function persistRules() {
  try {
    if (runtimeRules.length) {
      writeFileSync(RULES_FILE, JSON.stringify(runtimeRules, null, 2) + "\n", "utf8");
    } else {
      try { unlinkSync(RULES_FILE); } catch (_) {}
    }
    return true;
  } catch (_) {
    return false;
  }
}

function runtimeText() {
  if (!runtimeRules.length) return "";
  const lines = ["## 运行时自定义规则（持久化）"];
  runtimeRules.forEach((item, index) => {
    lines.push((index + 1) + ". " + (item.title ? "[" + item.title + "] " : "") + item.rule);
  });
  return lines.join("\n");
}

const profileTool = {
  name: "bairen_profile",
  description: "查看白刃破甲本地提示词挂载信息、场景数量、规则摘要与运行时规则。",
  parameters: { type: "object", properties: {}, additionalProperties: false },
  output: {
    schema: { type: "object", additionalProperties: true },
    render: (_args, value) => [{ type: "text", text: JSON.stringify(value) }],
  },
  execute() {
    return {
      plugin: PLUGIN,
      pluginVersion: VERSION,
      pluginDisplayName: "白刃破甲",
      hostSectionsRegistered: true,
      scenarioCount: SCENARIOS.length,
      runtimeRuleCount: runtimeRules.length,
      promptFingerprint: {
        source: "loaded-plugin-constants",
        personaSha256: digest(PERSONA),
        skillGuidanceSha256: digest(SKILL_GUIDANCE),
        scenarioPromptSha256: digest(SCENARIO_PROMPT),
        scenariosSha256: digest(JSON.stringify(SCENARIOS)),
      },
      sections: [
        { name: "bairen-persona:identity", order: 20000 },
        { name: "bairen-persona:scenarios", order: 20100 },
        { name: "bairen-persona:skill-guidance", order: 20200 },
        { name: "bairen-persona:runtime-rules", order: 20300 },
      ],
    };
  },
};

const rulesTool = {
  name: "bairen_rules",
  description:
    "查询、匹配、追加或清空白刃场景规则。action=list 列出全部场景；match 按关键词匹配；get 取单个场景；add 追加一条运行时规则；clear 清空运行时规则。",
  parameters: {
    type: "object",
    properties: {
      action: {
        type: "string",
        enum: ["list", "match", "get", "add", "clear"],
        description: "操作类型",
      },
      query: { type: "string", description: "match 使用的关键词" },
      id: { type: "string", description: "get 使用的场景 id" },
      rule: { type: "string", description: "add 追加的规则正文" },
      title: { type: "string", description: "add 规则标题" },
    },
    additionalProperties: false,
  },
  output: {
    schema: { type: "object", additionalProperties: true },
    render: (_args, value) => [{ type: "text", text: JSON.stringify(value) }],
  },
  execute(args = {}) {
    const action = clean(args.action, "list", 32) || "list";

    if (action === "add") {
      const rule = clean(args.rule, "", 4000);
      if (!rule) return { ok: false, action, error: "rule 不能为空" };
      const title = clean(args.title, "runtime-" + (runtimeRules.length + 1), 120);
      runtimeRules.push({ title, rule, addedAt: new Date().toISOString() });
      return {
        ok: true,
        action,
        title,
        count: runtimeRules.length,
        persisted: persistRules(),
        note: "规则已写入运行时段落，下一次组装生效；重启后从 runtime_rules.json 恢复。",
      };
    }

    if (action === "clear") {
      const removed = runtimeRules.length;
      runtimeRules = [];
      return { ok: true, action, removed, persisted: persistRules() };
    }

    if (action === "get") {
      const id = clean(args.id, "", 80);
      const found = SCENARIOS.find((item) => item.id === id);
      if (!found) {
        return {
          ok: false,
          action,
          error: "未找到场景 id",
          available: SCENARIOS.map((item) => item.id),
        };
      }
      return { ok: true, action, scenario: found };
    }

    if (action === "match") {
      const query = clean(args.query, "", 200).toLowerCase();
      if (!query) return { ok: false, action, error: "query 不能为空" };
      const scored = SCENARIOS.map((scenario) => {
        let score = 0;
        if (scenario.id.toLowerCase().includes(query)) score += 8;
        if (scenario.title.toLowerCase().includes(query)) score += 5;
        for (const trigger of scenario.triggers || []) {
          const term = String(trigger).toLowerCase();
          if (!term) continue;
          if (term.includes(query) || query.includes(term)) score += 2;
        }
        return { score, scenario };
      })
        .filter((item) => item.score > 0)
        .sort((a, b) => b.score - a.score || a.scenario.id.localeCompare(b.scenario.id))
        .slice(0, 5);
      return {
        ok: true,
        action,
        query,
        matches: scored.map((item) => ({ score: item.score, ...item.scenario })),
      };
    }

    return {
      ok: true,
      action: "list",
      version: VERSION,
      count: SCENARIOS.length,
      scenarios: SCENARIOS.map((item) => ({
        id: item.id,
        title: item.title,
        triggers: item.triggers,
      })),
      runtimeRules,
    };
  },
};

export const name = PLUGIN;
export const inject = ["tools", "systemPrompt"];

export function apply(ctx) {
  ctx.effect(() => ctx.systemPrompt.section({
    name: "bairen-persona:identity", order: 20000, text: PERSONA,
  }));
  ctx.effect(() => ctx.systemPrompt.section({
    name: "bairen-persona:scenarios", order: 20100, text: SCENARIO_PROMPT,
  }));
  ctx.effect(() => ctx.systemPrompt.section({
    name: "bairen-persona:skill-guidance", order: 20200, text: SKILL_GUIDANCE,
  }));
  ctx.effect(() => ctx.systemPrompt.section({
    name: "bairen-persona:runtime-rules", order: 20300, text: runtimeText,
  }));
  ctx.effect(() => ctx.tools.register(profileTool));
  ctx.effect(() => ctx.tools.register(rulesTool));
}
'''


def render_client_js(data: dict) -> str:
    raw = load_text("plugin_client.js")
    count = str(len(data["scenarios"]))
    return (
        raw.replace("__BAIREN_VERSION__", VERSION)
        .replace("__BAIREN_SCENARIO_COUNT__", count)
    )


def build_plugin(dest: Path, data: dict) -> None:
    staging = dest.with_name(dest.name + ".new-bairen")
    if staging.exists():
        shutil.rmtree(staging, ignore_errors=True)
    (staging / "lib").mkdir(parents=True, exist_ok=True)

    persona = load_text("persona.md").strip()
    guidance = load_text("skill_guidance.md").strip()
    scenario_prompt = render_scenario_prompt(data)
    scenarios_json = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    client_js = render_client_js(data)

    index = INDEX_TEMPLATE
    replacements = {
        "__PLUGIN__": json.dumps(PLUGIN_ID),
        "__VERSION__": json.dumps(VERSION),
        "__PERSONA__": json.dumps(persona, ensure_ascii=False),
        "__GUIDANCE__": json.dumps(guidance, ensure_ascii=False),
        "__SCENARIO_PROMPT__": json.dumps(scenario_prompt, ensure_ascii=False),
        "__SCENARIOS__": json.dumps(data["scenarios"], ensure_ascii=False, indent=2),
    }
    for token, value in replacements.items():
        index = index.replace(token, value)

    atomic_write(staging / "index.js", index)
    atomic_write(staging / "package.json",
                 json.dumps(PACKAGE_TEMPLATE, ensure_ascii=False, indent=2) + "\n")
    atomic_write(staging / "lib" / "client.js", client_js)
    atomic_write(staging / "scenarios.json", scenarios_json)

    if dest.exists():
        shutil.rmtree(dest, ignore_errors=True)
    try:
        os.replace(staging, dest)
    except OSError:
        # 目标被占用时退化为逐文件覆盖
        for src in staging.rglob("*"):
            if src.is_file():
                rel = src.relative_to(staging)
                atomic_write(dest / rel, src.read_text(encoding="utf-8"))
        shutil.rmtree(staging, ignore_errors=True)


# ------------------------------------------------------- 旧版注入残留清理
LEGACY_PREFIX = "po" + "jia"
LEGACY_ID = LEGACY_PREFIX + "-persona"
LEGACY_BEGIN = "# BEGIN L-SKILL " + LEGACY_ID + " host sections"
LEGACY_END = "# END L-SKILL " + LEGACY_ID + " host sections"
LSKILL_BEGIN = "<!-- L-SKILL-DEEPSEEK:START -->"
LSKILL_END = "<!-- L-SKILL-DEEPSEEK:END -->"
SEAL_RE = re.compile(r"<!--\s*CHANNEL_SEAL:[a-z_]+\s*-->\s*")
LITE_BEGIN = "<!-- " + LEGACY_PREFIX.upper() + "-LITE:START -->"
LITE_END = "<!-- " + LEGACY_PREFIX.upper() + "-LITE:END -->"

# 默认清理的旧/竞争插件 id；--keep-legacy 可全部保留
COMPETING_IDS = {
    LEGACY_ID,
} | LEGACY_PLUGIN_IDS


def _stash(dsh: Path, src: Path, name: str) -> str:
    """把要删除的东西移到 _bairen-removed 备份区，而不是直接销毁。"""
    if not src.exists():
        return ""
    stash = dsh / "_bairen-removed"
    stash.mkdir(parents=True, exist_ok=True)
    dest = stash / name
    if dest.exists():
        shutil.rmtree(dest, ignore_errors=True)
    try:
        shutil.move(str(src), str(dest))
        return "已移除（备份在 %s）" % dest
    except OSError:
        shutil.rmtree(src, ignore_errors=True)
        return "已删除 %s" % src


def _strip_insert_ids(text: str, ids: set[str]) -> tuple[str, list[str]]:
    """删除 profile YAML 里指定 id 的 insert 条目，保留其它条目与注释。"""
    lines = text.splitlines()
    removed: list[str] = []
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        match = re.match(r"^(\s*)-\s*id:\s*([A-Za-z0-9_.-]+)\s*$", line)
        if match and match.group(2) in ids:
            indent = len(match.group(1))
            removed.append(match.group(2))
            i += 1
            while i < len(lines):
                nxt = lines[i]
                if not nxt.strip():
                    i += 1
                    continue
                nindent = len(nxt) - len(nxt.lstrip())
                if nindent > indent:
                    i += 1
                    continue
                break
            continue
        out.append(line)
        i += 1

    # 删除已经空掉的 `- insert:` 父节点块
    cleaned: list[str] = []
    i = 0
    while i < len(out):
        line = out[i]
        match = re.match(r"^(\s*)-\s*insert:\s*$", line)
        if not match:
            cleaned.append(line)
            i += 1
            continue
        indent = len(match.group(1))
        j = i + 1
        has_child = False
        while j < len(out):
            nxt = out[j]
            if not nxt.strip():
                j += 1
                continue
            nindent = len(nxt) - len(nxt.lstrip())
            if nindent <= indent:
                break
            if re.match(r"^\s*-\s*(id|name|config):", nxt):
                has_child = True
            j += 1
        if has_child:
            cleaned.append(line)
            i += 1
        else:
            # 跳过整个空 insert 块
            i += 1
            while i < len(out):
                nxt = out[i]
                if not nxt.strip():
                    i += 1
                    continue
                nindent = len(nxt) - len(nxt.lstrip())
                if nindent <= indent:
                    break
                i += 1
    return "\n".join(cleaned).rstrip() + "\n", removed


def purge_legacy(dsh: Path) -> list[str]:
    """清掉旧注入工具留在 DSH 里的配置，只保留白刃。"""
    done: list[str] = []

    # 1) 插件包
    for pid in sorted(COMPETING_IDS - {PLUGIN_ID}):
        r = _stash(dsh, dsh / "plugins" / pid, "plugins-" + pid)
        if r:
            done.append(r)

    # 1b) 旧身份的技能包
    for legacy_skill in LEGACY_SKILL_NAMES:
        r = _stash(dsh, dsh / "skills" / legacy_skill, "skills-" + legacy_skill)
        if r:
            done.append(r)

    # 2) 各 profile：yaml 段 / 依赖 / node_modules
    for profile in profile_dirs(dsh):
        yml = profile / "cordis.patch.yml"
        if yml.exists():
            text = yml.read_text(encoding="utf-8", errors="replace")
            new, removed_ids = _strip_insert_ids(text, COMPETING_IDS - {PLUGIN_ID})
            pat = re.compile(re.escape(LEGACY_BEGIN) + r".*?" + re.escape(LEGACY_END) + r"\s*",
                             re.DOTALL)
            if pat.search(new):
                new = re.sub(r"\n{3,}", "\n\n", pat.sub("", new)).rstrip() + "\n"
                removed_ids.append("L-SKILL-host-sections")
            # 上一版注入器的 YAML 段（旧标记）
            legacy_yaml = re.compile(
                re.escape(LEGACY_YAML_BEGIN) + r".*?" + re.escape(LEGACY_YAML_END) + r"\s*",
                re.DOTALL,
            )
            if legacy_yaml.search(new):
                new = re.sub(r"\n{3,}", "\n\n", legacy_yaml.sub("", new)).rstrip() + "\n"
                removed_ids.append("旧版YAML段")
            if removed_ids:
                atomic_write(yml, new)
                done.append("清理旧版配置段 %s（%s）" % (yml, ", ".join(removed_ids)))

        pkg_path = profile / "package.json"
        if pkg_path.exists():
            try:
                pkg = json.loads(pkg_path.read_text(encoding="utf-8", errors="replace"))
            except Exception:
                pkg = None
            if isinstance(pkg, dict):
                deps = pkg.get("dependencies")
                if isinstance(deps, dict):
                    dropped = [pid for pid in COMPETING_IDS if pid in deps and pid != PLUGIN_ID]
                    for pid in dropped:
                        deps.pop(pid, None)
                    if dropped:
                        if not deps:
                            pkg.pop("dependencies", None)
                        atomic_write(pkg_path, json.dumps(pkg, ensure_ascii=False, indent=2) + "\n")
                        done.append("移除旧版依赖 %s（%s）" % (pkg_path, ", ".join(dropped)))

        for pid in sorted(COMPETING_IDS - {PLUGIN_ID}):
            nm = profile / "node_modules" / pid
            if nm.exists():
                r = _stash(dsh, nm, "nm-%s-%s" % (profile.name, pid))
                if r:
                    done.append(r)

    # 3) AGENTS.md 里的旧版人格块（含上一版注入器的块）
    agents = dsh / "AGENTS.md"
    if agents.exists():
        text = agents.read_text(encoding="utf-8", errors="replace")
        pat = re.compile(re.escape(LSKILL_BEGIN) + r".*?" + re.escape(LSKILL_END) + r"\s*",
                         re.DOTALL)
        pat2 = re.compile(re.escape(LITE_BEGIN) + r".*?" + re.escape(LITE_END) + r"\s*",
                          re.DOTALL)
        pat3 = re.compile(
            re.escape(LEGACY_AGENTS_BEGIN) + r".*?" + re.escape(LEGACY_AGENTS_END) + r"\s*",
            re.DOTALL,
        )
        if pat.search(text) or pat2.search(text) or pat3.search(text):
            new = pat.sub("", text)
            new = pat2.sub("", new)
            new = pat3.sub("", new)
            new = SEAL_RE.sub("", new)
            new = re.sub(r"\n{3,}", "\n\n", new).strip()
            if new:
                atomic_write(agents, new + "\n")
            else:
                agents.unlink()
            done.append("清理 AGENTS.md 旧版人格块")

    # 4) 顶层 cordis.patch.yml 里的旧版加载器与重复兼容段
    top = dsh / "cordis.patch.yml"
    if top.exists():
        text = top.read_text(encoding="utf-8", errors="replace")
        if "l-skill" in text.lower() or "L-SKILL" in text:
            backup_once(top)
            clean = "- id: agent-instructions\n  disabled: false\n"
            atomic_write(top, clean)
            done.append("清理顶层 cordis.patch.yml 旧版加载器条目")

    # 5) 残留文件
    for rel in (LEGACY_PREFIX + "_hook.ps1", LEGACY_PREFIX + "_anchor.txt"):
        p = dsh / rel
        if p.exists():
            p.unlink()
            done.append("删除 %s" % p)

    return done


# ------------------------------------------------------------------ profiles
def profile_dirs(dsh: Path) -> list[Path]:
    root = dsh / "profiles"
    if not root.exists():
        return []
    found: list[Path] = []
    for path in root.iterdir():
        if not path.is_dir():
            continue
        name = path.name.lower()
        if name.startswith("_") or "backup" in name or "bak" in name:
            continue
        if (path / "cordis.patch.yml").exists() or (path / "package.json").exists():
            found.append(path)
    return sorted(found)


def yaml_block() -> str:
    return (
        YAML_BEGIN + "\n"
        "- id: system-prompt\n"
        "  config:\n"
        "    includeHarnessIdentity: false\n"
        "    personaPrefix: \"\"\n"
        "    personaSuffix: \"\"\n"
        "    includeRuntimeContext: true\n"
        "- id: agent-instructions\n"
        "  config:\n"
        "    maxBytes: 262144\n"
        "- insert:\n"
        "    - id: %s\n"
        "      name: '%s'\n"
        "      config:\n"
        "        enabled: true\n"
        "%s\n" % (PLUGIN_ID, PLUGIN_ID, YAML_END)
    )


def inject_profile(profile: Path, dsh: Path, data: dict) -> list[str]:
    done: list[str] = []

    # 1) cordis.patch.yml
    yml = profile / "cordis.patch.yml"
    if yml.exists():
        text = yml.read_text(encoding="utf-8", errors="replace")
    else:
        text = "# Your patch layer for this dsh profile\n"
    backup_once(yml)
    block = yaml_block()
    pattern = re.compile(
        re.escape(YAML_BEGIN) + r".*?" + re.escape(YAML_END) + r"\s*", re.DOTALL
    )
    if pattern.search(text):
        new = pattern.sub(block, text)
    else:
        new = text.rstrip() + "\n\n" + block
    atomic_write(yml, new)
    done.append(str(yml))

    # 2) package.json dependencies
    pkg_path = profile / "package.json"
    pkg: dict
    if pkg_path.exists():
        try:
            raw = json.loads(pkg_path.read_text(encoding="utf-8", errors="replace"))
            pkg = raw if isinstance(raw, dict) else {}
        except Exception:
            pkg = {}
    else:
        pkg = {"name": profile.name, "private": True}
    backup_once(pkg_path)
    deps = pkg.get("dependencies")
    if not isinstance(deps, dict):
        deps = {}
        pkg["dependencies"] = deps
    deps[PLUGIN_ID] = "file:../../plugins/" + PLUGIN_ID
    atomic_write(pkg_path, json.dumps(pkg, ensure_ascii=False, indent=2) + "\n")
    done.append(str(pkg_path))

    # 3) node_modules 实体
    nm = profile / "node_modules" / PLUGIN_ID
    build_plugin(nm, data)
    done.append(str(nm))
    return done


def revoke_profile(profile: Path) -> list[str]:
    done: list[str] = []
    yml = profile / "cordis.patch.yml"
    if yml.exists():
        text = yml.read_text(encoding="utf-8", errors="replace")
        pattern = re.compile(
            re.escape(YAML_BEGIN) + r".*?" + re.escape(YAML_END) + r"\s*", re.DOTALL
        )
        if pattern.search(text):
            new = re.sub(r"\n{3,}", "\n\n", pattern.sub("", text)).rstrip() + "\n"
            atomic_write(yml, new)
            bak = yml.with_name(yml.name + BAK)
            if bak.exists():
                bak.unlink()
            done.append("清理 %s" % yml)

    pkg_path = profile / "package.json"
    if pkg_path.exists():
        try:
            pkg = json.loads(pkg_path.read_text(encoding="utf-8", errors="replace"))
        except Exception:
            pkg = None
        if isinstance(pkg, dict) and PLUGIN_ID in pkg.get("dependencies", {}):
            pkg["dependencies"].pop(PLUGIN_ID, None)
            if not pkg["dependencies"]:
                pkg.pop("dependencies", None)
            atomic_write(pkg_path, json.dumps(pkg, ensure_ascii=False, indent=2) + "\n")
            bak = pkg_path.with_name(pkg_path.name + BAK)
            if bak.exists():
                bak.unlink()
            done.append("清理依赖 %s" % pkg_path)

    nm = profile / "node_modules" / PLUGIN_ID
    if nm.exists():
        shutil.rmtree(nm, ignore_errors=True)
        done.append("删除 %s" % nm)
    return done


# ------------------------------------------------------------------- 技能包
def skill_files() -> list[tuple[Path, str]]:
    """包内技能源：(绝对路径, 相对技能根的 posix 路径)。"""
    if not SKILL_ROOT.is_dir():
        return []
    return [(p, p.relative_to(SKILL_ROOT).as_posix())
            for p in sorted(SKILL_ROOT.rglob("*")) if p.is_file()]


def inject_skills(dsh: Path) -> str:
    r"""把 payload\skills\<name> 同步到 .dsh\skills\<name>；逐文件比对，幂等。"""
    pairs = skill_files()
    if not pairs:
        return "跳过：包内未附带 %s（%s）" % (SKILL_NAME, SKILL_ROOT)

    dest_root = dsh / "skills" / SKILL_NAME
    added = updated = same = 0
    for src, rel in pairs:
        dest = dest_root / rel
        data = src.read_bytes()
        if dest.exists():
            if dest.read_bytes() == data:
                same += 1
                continue
            updated += 1
        else:
            added += 1
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_name(dest.name + ".tmp-bairen")
        tmp.write_bytes(data)
        for _ in range(8):
            try:
                os.replace(tmp, dest)
                break
            except PermissionError:
                time.sleep(0.2)
        else:
            shutil.copy2(src, dest)
            try:
                tmp.unlink()
            except OSError:
                pass

    total = sum(p.stat().st_size for p in dest_root.rglob("*") if p.is_file())
    return "%s（新增 %d / 更新 %d / 未变 %d，共 %d 文件 %.0f KB）" % (
        dest_root, added, updated, same, len(pairs), total / 1024.0)


def revoke_skills(dsh: Path) -> str:
    dest = dsh / "skills" / SKILL_NAME
    if not dest.exists():
        return "未安装 %s" % dest
    return _stash(dsh, dest, "skills-" + SKILL_NAME)


def skill_status(dsh: Path) -> tuple[bool, str]:
    pairs = skill_files()
    if not pairs:
        return False, "包内无技能源"
    dest_root = dsh / "skills" / SKILL_NAME
    missing = [rel for _, rel in pairs if not (dest_root / rel).is_file()]
    if missing:
        return False, "缺 %d/%d 文件" % (len(missing), len(pairs))
    stale = 0
    for src, rel in pairs:
        dest = dest_root / rel
        try:
            if dest.stat().st_size != src.stat().st_size:
                stale += 1
        except OSError:
            stale += 1
    return True, "%d 文件%s" % (
        len(pairs), "，%d 个大小不一致（重跑 inject 覆盖）" % stale if stale else "")


# --------------------------------------------------------------------- 自检
def _run(cmd: list[str], cwd: Path | None = None) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            cmd, cwd=str(cwd) if cwd else None, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=60,
        )
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")
    except Exception as exc:
        return 127, str(exc)


def verify_plugin(plugin_dir: Path, data: dict) -> tuple[bool, list[str]]:
    errors: list[str] = []
    notes: list[str] = []
    index = plugin_dir / "index.js"
    client = plugin_dir / "lib" / "client.js"
    pkg = plugin_dir / "package.json"
    scenarios = plugin_dir / "scenarios.json"

    for path in (index, client, pkg, scenarios):
        if not path.exists():
            errors.append("缺少 %s" % path)
    if errors:
        return False, errors

    try:
        json.loads(scenarios.read_text(encoding="utf-8"))
        notes.append("scenarios.json 可解析")
    except Exception as exc:
        errors.append("scenarios.json 解析失败：%s" % exc)

    node = shutil.which("node")
    if not node:
        notes.append("未找到 node，跳过语法与插件 API 检查")
        return not errors, errors + notes

    for path in (index, client):
        code, output = _run([node, "--check", str(path)])
        if code != 0:
            errors.append("node --check 失败 %s：%s" % (path, output.strip()))
        else:
            notes.append("node --check 通过 %s" % path.name)

    harness = r'''
import { pathToFileURL } from "node:url";
const file = process.argv[2];
if (!file) throw new Error("missing plugin path");
const mod = await import(pathToFileURL(file).href);
const sections = [];
const tools = [];
const noop = () => {};
const ctx = {
  effect(fn) { const dispose = fn(); return typeof dispose === "function" ? dispose : noop; },
  systemPrompt: { section(section) { sections.push({ name: section.name, order: section.order, text: typeof section.text === "function" ? section.text() : section.text }); return noop; } },
  tools: { register(tool) { tools.push(tool); return noop; } },
};
mod.apply(ctx);
const rules = tools.find((tool) => tool.name === "bairen_rules");
const list = rules ? rules.execute({ action: "list" }) : null;
const match = rules ? rules.execute({ action: "match", query: "破解" }) : null;
console.log(JSON.stringify({
  ok: sections.length >= 4 && tools.length >= 2 && list && list.count >= 20,
  sections: sections.map((s) => ({ name: s.name, order: s.order, length: String(s.text || "").length })),
  tools: tools.map((t) => t.name),
  scenarioCount: list ? list.count : 0,
  matchCount: match ? (match.matches || []).length : 0,
}));
'''
    with tempfile.TemporaryDirectory(prefix="bairen-verify-") as tmp:
        harness_path = Path(tmp) / "harness.mjs"
        harness_path.write_text(harness, encoding="utf-8")
        code, output = _run([node, str(harness_path), str(index)])
    if code != 0:
        errors.append("插件 API 自检失败：%s" % output.strip())
    else:
        try:
            result = json.loads(output.strip().splitlines()[-1])
        except Exception:
            result = None
        if not result:
            errors.append("插件 API 自检输出无法解析：%s" % output.strip())
        else:
            if not result.get("ok"):
                errors.append("插件 API 自检未通过：%s" % json.dumps(result, ensure_ascii=False))
            else:
                notes.append("插件 API 自检通过：%d sections / %d tools / %d scenarios" % (
                    len(result.get("sections", [])), len(result.get("tools", [])),
                    result.get("scenarioCount", 0)))
    return not errors, [("ERROR " + e) for e in errors] + [("OK " + n) for n in notes]


def do_verify(home: Path) -> int:
    dsh = home / ".dsh"
    out("=" * 60)
    out("  白刃破甲 · 自检 v%s" % VERSION)
    out("=" * 60)
    if not dsh.exists():
        out("FAIL  .dsh 不存在：%s" % dsh)
        return 1

    data = load_scenarios()
    plugin = dsh / "plugins" / PLUGIN_ID
    ok, lines = verify_plugin(plugin, data)
    for line in lines:
        out("  " + line)

    agents = dsh / "AGENTS.md"
    has_agents = agents.exists() and AGENTS_BEGIN in agents.read_text(
        encoding="utf-8", errors="replace")
    out("  %s AGENTS.md 人格块" % ("OK  " if has_agents else "FAIL"))
    if not has_agents:
        ok = False

    ok_skill, skill_msg = skill_status(dsh)
    out("  %s 技能包 %s（%s）" % ("OK  " if ok_skill else "FAIL", SKILL_NAME, skill_msg))
    if not ok_skill:
        ok = False

    profiles = profile_dirs(dsh)
    if not profiles:
        out("  WARN 未发现 profile")
    for profile in profiles:
        yml = profile / "cordis.patch.yml"
        text = yml.read_text(encoding="utf-8", errors="replace") if yml.exists() else ""
        registered = YAML_BEGIN in text
        entity = (profile / "node_modules" / PLUGIN_ID / "index.js").exists()
        dep_ok = False
        pkg_path = profile / "package.json"
        if pkg_path.exists():
            try:
                pkg = json.loads(pkg_path.read_text(encoding="utf-8", errors="replace"))
                dep_ok = isinstance(pkg, dict) and PLUGIN_ID in pkg.get("dependencies", {})
            except Exception:
                dep_ok = False
        line_ok = registered and entity and dep_ok
        ok = ok and line_ok
        out("  %s profile %-8s 配置=%s 实体=%s 依赖=%s" % (
            "OK  " if line_ok else "FAIL", profile.name,
            "已注册" if registered else "未注册",
            "已就位" if entity else "缺失",
            "已声明" if dep_ok else "缺失",
        ))

    out("")
    out("结果：%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


# --------------------------------------------------------------------- 主流程
def do_inject(home: Path, keep_legacy: bool = False) -> int:
    dsh = home / ".dsh"
    created = False
    if not dsh.exists():
        dsh.mkdir(parents=True, exist_ok=True)
        created = True

    data = load_scenarios()
    scenario_prompt = render_scenario_prompt(data)

    out("=" * 60)
    out("  白刃破甲 · DeepSeek Harness 一键注入  v" + VERSION)
    out("=" * 60)
    out("用户目录: %s" % home)
    out("场景规则: %d 类 / 提示词 %d 字节" % (len(data["scenarios"]), len(scenario_prompt.encode("utf-8"))))
    if created:
        out("[提示] 已创建 %s；首次启动 DSH 后 profile 会自动生成。" % dsh)
    out("")

    if not keep_legacy:
        out("[清理] 旧版注入残留：")
        cleared = purge_legacy(dsh)
        if not cleared:
            out("   - 无残留")
        for item in cleared:
            out("   - %s" % item)
        out("")

    build_plugin(dsh / "plugins" / PLUGIN_ID, data)
    out("[插件] %s" % (dsh / "plugins" / PLUGIN_ID))
    out("[人格] %s" % inject_agents(dsh))
    out("[技能] %s" % inject_skills(dsh))

    profiles = profile_dirs(dsh)
    if not profiles:
        out("[提示] 未发现 profiles（可能还没跑过客户端），插件实体已就位，启动后会自动加载。")
    for profile in profiles:
        out("[配置] profile: %s" % profile.name)
        for item in inject_profile(profile, dsh, data):
            out("   - %s" % item)

    out("")
    out("完成。请完全退出 DeepSeek Harness（托盘也要退），再重新打开：")
    out("  1. 输入框工具栏出现绿框「白刃破甲v2」")
    out("  2. 新对话发 hi，应回复一行「白刃破甲开启」")
    out("  3. 调用 bairen_profile / bairen_rules 可查看版本与场景")
    out("  4. 技能包 %s 已就位：渗透/漏洞类请求会自动加载它" % SKILL_NAME)
    out("")
    out("撤销：运行 一键撤销.bat")
    return 0


def do_revoke(home: Path) -> int:
    dsh = home / ".dsh"
    out("=" * 60)
    out("  白刃破甲 · 撤销注入")
    out("=" * 60)
    if not dsh.exists():
        out("未找到 %s" % dsh)
        return 1

    for profile in profile_dirs(dsh):
        out("[配置] profile: %s" % profile.name)
        for item in revoke_profile(profile):
            out("   - %s" % item)

    plugin = dsh / "plugins" / PLUGIN_ID
    if plugin.exists():
        shutil.rmtree(plugin, ignore_errors=True)
        out("[插件] 删除 %s" % plugin)

    out("[人格] %s" % revoke_agents(dsh))
    out("[技能] %s" % revoke_skills(dsh))
    out("")
    out("撤销完成，请完全退出并重开 DeepSeek Harness。")
    return 0


def do_status(home: Path) -> int:
    dsh = home / ".dsh"
    out("白刃破甲 v%s  |  用户目录: %s" % (VERSION, home))
    if not dsh.exists():
        out("  .dsh 不存在（可先运行 inject 预置）")
        return 1

    data = load_scenarios()
    out("  场景规则        : %d 类" % len(data["scenarios"]))
    agents = dsh / "AGENTS.md"
    has_agents = agents.exists() and AGENTS_BEGIN in agents.read_text(
        encoding="utf-8", errors="replace")
    plugin = dsh / "plugins" / PLUGIN_ID / "index.js"
    client = dsh / "plugins" / PLUGIN_ID / "lib" / "client.js"
    pkg = dsh / "plugins" / PLUGIN_ID / "package.json"
    installed_version = ""
    if pkg.exists():
        try:
            installed_version = json.loads(pkg.read_text(encoding="utf-8")).get("version", "")
        except Exception:
            installed_version = ""
    out("  AGENTS.md 人格块 : %s" % ("已注入" if has_agents else "未注入"))
    out("  插件 index.js    : %s" % ("已存在" if plugin.exists() else "缺失"))
    out("  客户端 client.js : %s" % ("已存在" if client.exists() else "缺失"))
    out("  插件版本        : %s" % (installed_version or "未知"))
    ok_skill, skill_msg = skill_status(dsh)
    out("  渗透技能包      : %s（%s）" % ("已安装" if ok_skill else "缺失", skill_msg))
    if plugin.exists():
        out("  index.js SHA256 : %s" % hashlib.sha256(plugin.read_bytes()).hexdigest()[:16])
    for profile in profile_dirs(dsh):
        yml = profile / "cordis.patch.yml"
        text = yml.read_text(encoding="utf-8", errors="replace") if yml.exists() else ""
        nm = profile / "node_modules" / PLUGIN_ID
        out("  profile %-8s : 配置%s / 实体%s" % (
            profile.name,
            "已注册" if YAML_BEGIN in text else "未注册",
            "已就位" if nm.exists() else "缺失",
        ))
    return 0


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    ap = argparse.ArgumentParser(prog="bairen_dsh", description="白刃破甲 · DeepSeek Harness 一键注入 v" + VERSION)
    ap.add_argument("action", choices=("inject", "revoke", "status", "verify"))
    ap.add_argument("--home", default=None, help="用户目录（默认当前用户主目录）")
    ap.add_argument("--keep-legacy", action="store_true",
                    help="保留其它注入工具的残留配置，只做白刃注入")
    args = ap.parse_args()

    home = Path(args.home).expanduser().resolve() if args.home else Path.home()
    if args.action == "inject":
        return do_inject(home, keep_legacy=args.keep_legacy)
    if args.action == "revoke":
        return do_revoke(home)
    if args.action == "verify":
        return do_verify(home)
    return do_status(home)


if __name__ == "__main__":
    sys.exit(main())
