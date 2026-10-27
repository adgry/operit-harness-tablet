/* No-build client extension. Upstream Claude Style and OFM are unmodified. */
window.__ModuleLoader__.load({
  id: 'operit-tablet-ui',
  factory: require => {
    const { createElement: h } = require('react');
    const disposers = new WeakMap();
    const CSS = String.raw`
/* Preserve the chosen compact studio composition at desktop widths. */
@media(min-width:1100px){.dsh-claude-home-panel{margin-top:30px!important}}
/* Scope the palette to OFM and its portaled surfaces, leaving semantic state
   colors and the official theme's light/dark preference under upstream control. */
:is(.ofm_root,.ofm_scrim,.ofm_toasts,.operit-device-settings){
  --dsw-alias-bg-layer-1:#F9F9F6;--dsw-alias-bg-layer-2:#FCFCFB;--dsw-alias-bg-layer-3:#FBFBF9;
  --dsw-alias-border-l1:#E8E6DC;--dsw-alias-border-l2:#E8E6DC;
  --dsw-alias-label-primary:#141413;--dsw-alias-label-secondary:#6E6A60;
  --dsw-alias-label-tertiary:#6E6A60;--dsw-alias-state-business-primary:#D97757;
  color:#141413;font-family:inherit;font-size:13px;line-height:1.55;color-scheme:light;
}
[data-shortcut-modal="settings"]:has(.ofm_root){width:min(1120px,calc(100vw - 48px))!important;max-width:calc(100vw - 48px)!important;height:min(900px,calc(100dvh - 48px))!important;max-height:calc(100dvh - 48px)!important}
[data-shortcut-modal="settings"]:has(.ofm_root) > nav button{min-height:44px!important}
[data-shortcut-modal="settings"]:has(.ofm_root) [data-dsh-claude-part="host-root"]{max-width:none!important}
.ofm_root.ofm_shell{width:100%;min-width:0;max-width:none!important;gap:16px!important}
.ofm_root::before,.ofm_hero::before,.ofm_sponsor_banner::before,.ofm_sponsor_banner::after{content:none!important}
.ofm_root :is(.ofm_hero,.ofm_panel,.ofm_card,.ofm_nav,.ofm_recocard,.ofm_chan,.ofm_glass,.ofm_ann){background:#FCFCFB!important;border-color:#E8E6DC!important;box-shadow:none!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important;border-radius:10px!important}
.ofm_root :is(.ofm_card,.ofm_recocard,.ofm_chan):hover{transform:none!important;box-shadow:none!important;border-color:#C9C6BA!important}
.ofm_root .ofm_hero{padding:18px!important;gap:12px!important}
.ofm_root .ofm_panel{padding:16px!important}
.ofm_root .ofm_pagetitle h2{font-size:20px!important;font-weight:600!important;line-height:1.3!important;letter-spacing:-.25px!important;background:none!important;-webkit-text-fill-color:currentColor!important;color:#141413!important}
.ofm_root :is(.ofm_pagesub,.ofm_note){line-height:1.6!important}
.ofm_root .ofm_nav{position:sticky;top:0;padding:10px!important;gap:8px!important;z-index:30;background:#FBFBF9!important}
.ofm_root .ofm_tabs{gap:4px!important;padding:4px!important;border-radius:8px!important;background:#F9F9F6!important;box-shadow:none!important;min-width:0}
.ofm_root .ofm_tab{min-height:44px!important;padding:8px 10px!important;border-radius:6px!important;font-size:12px!important;line-height:1.4!important;white-space:normal!important}
.ofm_root .ofm_tab[data-on="true"]{background:#FCFCFB!important;color:#141413!important;box-shadow:0 1px 2px #14141312!important}
.ofm_root .ofm_tabglider{display:none!important}
.ofm_root .ofm_navbrand{font-size:13px!important;font-weight:600!important}
.ofm_root .ofm_navmark{width:24px!important;height:24px!important}
.ofm_root .ofm_grid{grid-template-columns:repeat(auto-fit,minmax(min(250px,100%),1fr))!important;gap:12px!important}
.ofm_root :is(.ofm_two,.ofm_duo){min-width:0}
.ofm_root :is(.ofm_card,.ofm_panel,.ofm_hero,.ofm_chan,.ofm_field,.ofm_row,.ofm_recofacts,.ofm_page){min-width:0;max-width:100%;box-sizing:border-box}
.ofm_root :is(.ofm_cardname,.ofm_chanmeta,.ofm_acctmeta,.ofm_recobody){min-width:0}
.ofm_root :is(.ofm_pagesub,.ofm_note,.ofm_error,.ofm_callout,.ofm_snippet){overflow-wrap:anywhere}
.ofm_root :is(.ofm_btn,.ofm_minibtn,.ofm_foldtoggle,.ofm_starbtn,.ofm_starlink,.ofm_recostar,.ofm_sponsor_cta),.ofm_scrim :is(button,.ofm_btn){min-height:44px!important;box-sizing:border-box;line-height:1.4!important}
.ofm_root :is(.ofm_input,select,input:not([type="checkbox"]):not([type="radio"])),.ofm_scrim :is(.ofm_input,select,input:not([type="checkbox"]):not([type="radio"])){min-height:44px!important;max-width:100%;background:#FCFCFB!important;border-color:#E8E6DC!important;color:#141413!important;font:inherit}
.ofm_root .ofm_switch{min-height:44px!important;align-items:center!important}
.ofm_root :is(.ofm_seg,.ofm_chips){max-width:100%;flex-wrap:wrap}
.ofm_root .ofm_seg button{min-height:40px!important}
.ofm_root :is(.ofm_btn,.ofm_minibtn){box-shadow:none!important;border-radius:7px!important}
.ofm_root .ofm_sponsor_banner{grid-template-columns:40px minmax(0,1fr) auto!important;gap:12px!important;padding:14px!important;border:1px solid #E8E6DC!important;border-radius:10px!important;background:#F9F9F6!important;color:#141413!important;box-shadow:none!important}
.ofm_root .ofm_sponsor_logo{width:40px!important;height:40px!important;transform:none!important;box-shadow:none!important;border-radius:8px!important;background:transparent!important}
.ofm_root .ofm_sponsor_logo img{width:40px!important;height:40px!important;object-fit:contain}
.ofm_root .ofm_sponsor_bannerbody h3{font-size:16px!important;font-weight:600!important;line-height:1.4!important;color:#141413!important;-webkit-text-fill-color:#141413!important}
.ofm_root .ofm_sponsor_bannerbody p{font-size:12px!important;color:#6E6A60!important;line-height:1.55!important}
.ofm_root .ofm_sponsor_kicker{color:#6E6A60!important;background:transparent!important;border-color:#E8E6DC!important;box-shadow:none!important}
.ofm_root .ofm_sponsor_cta{background:#FCFCFB!important;color:#9B4F38!important;border:1px solid #E8E6DC!important;box-shadow:none!important;padding:9px 12px!important;transform:none!important}
/* Keep the upstream live level/ARIA label, but reduce its illustrative footprint. */
.ofm_root .ofm_tankrow{gap:16px!important;align-items:center!important}
.ofm_root .ofm_tank{width:120px!important;height:64px!important;flex:none!important;border-radius:7px!important;box-shadow:none!important}
.ofm_root .ofm_tankside{min-width:0!important;flex:1}
.ofm_root .ofm_poolstats{gap:8px!important;flex-wrap:wrap!important}
.ofm_root .ofm_stat{padding:8px 10px!important;border-radius:7px!important;box-shadow:none!important}
.ofm_root .ofm_tablewrap{width:100%;max-width:100%;overflow-x:auto!important;overscroll-behavior-inline:contain;-webkit-overflow-scrolling:touch}
.ofm_root .ofm_table{border-collapse:collapse;min-width:560px}
.ofm_root .ofm_table :is(th,td){padding:10px!important;border-color:#E8E6DC!important;font-size:12px!important}
.ofm_root .ofm_table th{background:#F9F9F6!important;color:#6E6A60!important}
.ofm_scrim{padding:24px!important;background:#14141352!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important}
.ofm_scrim :is(.ofm_modal,.ofm_ann){max-width:calc(100vw - 48px)!important;max-height:calc(100dvh - 48px)!important;background:#FCFCFB!important;border:1px solid #E8E6DC!important;border-radius:12px!important;box-shadow:0 12px 44px #14141322!important;backdrop-filter:none!important;-webkit-backdrop-filter:none!important}
.ofm_scrim :is(.ofm_modalhead,.ofm_annhead,.ofm_modalfoot,.ofm_annfoot){background:#FBFBF9!important;border-color:#E8E6DC!important}
.ofm_scrim :is(.ofm_modalbody,.ofm_annbody){min-height:0;overflow:auto!important;overscroll-behavior:contain}
.ofm_toasts .ofm_toast{background:#FCFCFB!important;border-color:#E8E6DC!important;color:#141413!important;box-shadow:0 4px 20px #14141314!important}
:is(.ofm_root,.ofm_scrim) :focus-visible,.operit-device-settings a:focus-visible{outline:2px solid #D97757!important;outline-offset:3px!important}
.operit-device-settings{max-width:680px;padding:8px 0}
.operit-device-settings h2{font-size:20px;font-weight:600;margin:0 0 12px}
.operit-device-settings p{color:#6E6A60;margin:0 0 16px}
.operit-device-settings a{display:inline-flex;align-items:center;min-height:44px;padding:0 16px;border:1px solid #E8E6DC;border-radius:8px;background:#FBFBF9;color:#9B4F38;text-decoration:none}
@media(max-width:900px){
 [data-shortcut-modal="settings"]:has(.ofm_root){width:calc(100vw - 32px)!important;max-width:calc(100vw - 32px)!important;height:calc(100dvh - 32px)!important;max-height:calc(100dvh - 32px)!important}
 [data-shortcut-modal="settings"]:has(.ofm_root){flex-direction:column!important}
 [data-shortcut-modal="settings"]:has(.ofm_root) > nav{width:100%!important;padding:12px 16px 0!important;gap:8px!important}
 [data-shortcut-modal="settings"]:has(.ofm_root) > nav > div:last-child{flex-direction:row!important;flex-wrap:wrap!important;overflow:visible!important;gap:4px!important}
 [data-shortcut-modal="settings"]:has(.ofm_root) > div{min-height:0!important}
 .ofm_root :is(.ofm_two,.ofm_duo){grid-template-columns:minmax(0,1fr)!important}
 .ofm_root .ofm_sponsor_banner{grid-template-columns:40px minmax(0,1fr)!important}
 .ofm_root .ofm_sponsor_cta{grid-column:2;justify-self:start}
 .ofm_root .ofm_pagehead .ofm_pills{margin-left:0!important}
}
@media(max-width:600px){
 .ofm_root .ofm_nav{grid-template-columns:minmax(0,1fr)!important}
 .ofm_root .ofm_navtools{justify-content:flex-start!important;flex-wrap:wrap!important}
 .ofm_root .ofm_tankrow{flex-wrap:wrap!important}
 .ofm_scrim{padding:12px!important}
 .ofm_scrim :is(.ofm_modal,.ofm_ann){max-width:calc(100vw - 24px)!important}
}
@media(prefers-reduced-motion:reduce){.ofm_root *,.ofm_scrim *{animation:none!important;transition:none!important}}
`;
    function dispose(ctx) {
      disposers.get(ctx)?.();
    }
    function apply(ctx) {
      dispose(ctx);
      let stopSlots = () => {};
      ctx.effect(() => {
        const style = document.createElement('style');
        style.setAttribute('data-plugin', 'operit-tablet-ui');
        style.textContent = CSS;
        document.head.appendChild(style);
        let active = true;
        const cleanup = () => {
          if (!active) return;
          active = false;
          style.remove();
          stopSlots();
          disposers.delete(ctx);
        };
        disposers.set(ctx, cleanup);
        return cleanup;
      }, 'operit-tablet-ui: scoped surfaces');
      // This is a navigation capability, never an invented JS/native status API.
      if (/(?:^|\s)OperitHarness\/0\.1(?:\s|$)/.test(navigator.userAgent)) {
        stopSlots = ctx.slots.inject('settings.section', () => ctx.slots.register({
          name: 'settings.section', id: 'operit-device', order: 40, label: () => 'Operit 设备',
        }, () => h('section', { className: 'operit-device-settings' },
          h('h2', null, 'Operit 设备'),
          h('p', null, '查看运行状态，管理设备权限与本地工具。'),
          h('a', { href: 'operit-harness://device' }, '打开设备与权限'))));
      }
    }
    return { name: 'operit-tablet-ui', inject: ['slots'], apply, dispose };
  },
});
