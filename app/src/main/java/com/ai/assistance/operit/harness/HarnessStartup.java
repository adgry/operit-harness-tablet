package com.ai.assistance.operit.harness;

/** Terminal presentation boundary; launch authorization remains in HarnessSecurity. */
public final class HarnessStartup {
    private HarnessStartup() {}
    public enum Stage { STOPPED, NATIVE, TERMINAL, PREREQUISITES, NODE, ENGINE, PLUGINS, STARTING, RUNNING, STOPPING, FAILED, TIMED_OUT }

    public static String clean(String raw) {
        return raw
            // OSC titles/hyperlinks end at BEL or ST; discard unfinished titles too.
            .replaceAll("(?:\u001b\\]|\u009d)[\\s\\S]*?(?:\u0007|\u001b\\\\|\u009c|$)", "")
            .replaceAll("\u001b[P^_X][\\s\\S]*?(?:\u001b\\\\|$)", "")
            .replaceAll("(?:\u001b\\[|\u009b)[0-?]*[ -/]*[@-~]", "")
            .replaceAll("\u001b[ -/]*[@-~]", "")
            .replaceAll("[\\p{Cc}&&[^\\n\\t]]", "").trim();
    }

    public static String diagnostic(String raw, String token) {
        String safe = clean(raw);
        if (!token.isEmpty()) safe = safe.replace(token, "[redacted]");
        return safe.replaceAll("(?i)([?&]token=)[^\\s&]+", "$1[redacted]")
            .replaceAll("(?i)(Bearer\\s+)[^\\s]+", "$1[redacted]");
    }

    public static Stage nextStage(Stage current, String line) {
        if (current == Stage.RUNNING) return current;
        switch (line) {
            case "operit-harness: phase=prerequisites": return Stage.PREREQUISITES;
            case "operit-harness: phase=node": return Stage.NODE;
            case "operit-harness: phase=engine": return Stage.ENGINE;
            case "operit-harness: phase=plugins": return Stage.PLUGINS;
            case "operit-harness: phase=start": return Stage.STARTING;
            default: return current;
        }
    }
}
