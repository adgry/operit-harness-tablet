package com.ai.assistance.operit.harness;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** The shell command contains only an app-private file path, never a credential. */
public final class HarnessSessionEnvironment {
    private HarnessSessionEnvironment() {}
    public static void write(File file, String runtime, String bridge, String token) throws IOException {
        if (!file.createNewFile()) throw new IOException("Session environment already exists");
        try {
            if (!file.setReadable(false, false) || !file.setWritable(false, false)
                || !file.setExecutable(false, false) || !file.setReadable(true, true)
                || !file.setWritable(true, true)) throw new IOException("Cannot secure session environment");
            String body="export OPERIT_RUNTIME_DIR="+HarnessSecurity.quote(runtime)
                +"\nexport OPERIT_BRIDGE_URL="+HarnessSecurity.quote(bridge)
                +"\nexport OPERIT_BRIDGE_TOKEN="+HarnessSecurity.quote(token)+"\n";
            try (FileOutputStream output=new FileOutputStream(file)) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException error) {
            file.delete();
            throw error;
        }
    }
    public static String command(String envPath, String scriptPath, String action) {
        if (!action.equals("install") && !action.equals("start") && !action.equals("stop"))
            throw new IllegalArgumentException("Unsupported runtime action");
        // Disable inherited tracing before sourcing: neither PTY echo nor debug logs see values.
        return "set +x; set +v; . "+HarnessSecurity.quote(envPath)+" && bash "+HarnessSecurity.quote(scriptPath)+" "+action;
    }
}
