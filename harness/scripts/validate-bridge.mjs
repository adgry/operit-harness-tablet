import { validateBridge } from './install-profile.mjs';
try { validateBridge(process.env.OPERIT_BRIDGE_URL, process.env.OPERIT_BRIDGE_TOKEN); }
catch (error) { console.error(`operit-harness: ${error.message}`); process.exitCode = 1; }
