/* 白刃破甲 · DeepSeek Harness 客户端插件 v4.0
 * 1) 在输入框工具栏（conversation.input.activity 插槽）渲染绿色状态徽标
 * 2) 兜底：把界面上任何旧版「xxx已激活」文本改写为白刃文案并隐藏旧徽标
 */
(() => {
  if (typeof window === "undefined" || !window.__ModuleLoader__) return;
  const ID = "bairen-persona";
  const VERSION = "4.0.1";
  const SCENARIO_COUNT = "24";
  const loader = window.__ModuleLoader__;

  if (window.__BAIREN_DSH_PILL__) return;
  window.__BAIREN_DSH_PILL__ = true;

  const factory = (require) => {
    const React = require("react");
    const h = React.createElement;

    const TEXT = "白刃破甲v2";
    // 旧版徽标关键字（转义存放，避免源码里出现明文产品名）
    const LEGACY_NAME = "\u7834\u7532\u52a9\u624b";
    const LEGACY_RE = new RegExp(LEGACY_NAME + "\\s*已激活", "g");

    const style = {
      pill: {
        display: "inline-flex",
        alignItems: "center",
        gap: 6,
        height: 24,
        padding: "0 10px",
        borderRadius: 999,
        background: "rgba(34,197,94,0.10)",
        border: "1px solid rgba(34,197,94,0.45)",
        color: "#16a34a",
        fontSize: 12,
        fontWeight: 500,
        lineHeight: "22px",
        whiteSpace: "nowrap",
        userSelect: "none",
        flex: "0 0 auto",
      },
      dot: {
        width: 7,
        height: 7,
        borderRadius: "50%",
        background: "#22c55e",
        boxShadow: "0 0 0 3px rgba(34,197,94,0.16)",
        flex: "0 0 auto",
      },
    };

    function Pill() {
      return h(
        "span",
        {
          style: style.pill,
          title: "白刃破甲 v" + VERSION + " · DeepSeek Harness · " + SCENARIO_COUNT + " 类场景规则",
          "data-bairen-pill": "1",
          "data-bairen-version": VERSION,
        },
        h("span", { style: style.dot, "aria-hidden": "true" }),
        h("span", null, TEXT)
      );
    }

    let scheduled = false;
    const legacyNodes = new WeakSet();

    /* 找到承载这句文案的最小徽标容器（排除本插件自己渲染的那个） */
    function badgeHost(node) {
      let el = node.parentElement;
      for (let i = 0; el && el !== document.body && i < 6; i++) {
        if (el.hasAttribute && el.hasAttribute("data-bairen-pill")) return null;
        const text = (el.textContent || "").trim();
        if (text.indexOf(TEXT) !== -1 && text.length <= TEXT.length + 8) return el;
        if (text.length > TEXT.length + 40) return null;
        el = el.parentElement;
      }
      return null;
    }

    function rewriteLegacyText() {
      if (typeof document === "undefined" || !document.body) return;
      const walker = document.createTreeWalker(
        document.body,
        NodeFilter.SHOW_TEXT,
        null
      );
      let node;
      while ((node = walker.nextNode())) {
        const value = node.nodeValue;
        if (!value) continue;
        const isLegacy = value.indexOf(LEGACY_NAME) !== -1 || legacyNodes.has(node);
        if (!isLegacy) continue;
        const next = value.replace(LEGACY_RE, TEXT);
        if (next !== value) {
          node.nodeValue = next;
          legacyNodes.add(node);
        }
        // 旧版徽标由其它程序注入：改成白刃文案后隐藏，避免出现两个绿框
        const host = badgeHost(node);
        if (host) host.style.display = "none";
      }
    }

    function scheduleRewrite() {
      if (scheduled) return;
      scheduled = true;
      const run = () => {
        scheduled = false;
        try {
          rewriteLegacyText();
        } catch (_) {}
      };
      if (typeof requestAnimationFrame === "function") requestAnimationFrame(run);
      else setTimeout(run, 16);
    }

    const inject = ["slots"];

    function apply(ctx) {
      try {
        ctx.slots.inject("conversation.input.activity", () =>
          ctx.slots.register(
            {
              name: "conversation.input.activity",
              id: "bairen-persona",
              order: 50,
            },
            Pill
          )
        );
      } catch (_) {}

      if (typeof document !== "undefined" && document.body) {
        scheduleRewrite();
        const observer = new MutationObserver(scheduleRewrite);
        observer.observe(document.body, {
          childList: true,
          subtree: true,
          characterData: true,
        });
        try {
          ctx.effect(() => () => observer.disconnect());
        } catch (_) {}
      } else if (typeof document !== "undefined") {
        document.addEventListener("DOMContentLoaded", scheduleRewrite, {
          once: true,
        });
      }
    }

    return { name: ID, inject, apply };
  };

  // 内置 bundle 走 load；外部/兼容加载器走 define。两者都可用，重复注册无副作用。
  try {
    if (typeof loader.define === "function") loader.define(ID, factory);
  } catch (_) {}
  try {
    if (typeof loader.load === "function") loader.load({ id: ID, factory });
  } catch (_) {}
})();
