package com.ai.assistance.operit.harness;

import org.junit.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.nio.file.attribute.PosixFilePermission;
import static org.junit.Assert.*;

public class HarnessSessionEnvironmentTest {
    @Test public void commandsSourcePrivateEnvironmentWithoutEchoingCredentials() throws Exception {
        Path dir=Files.createTempDirectory(Paths.get("/Volumes/D盘/软件数据/tmp"),"harness-env-");
        Path env=dir.resolve("session ' $(bad).env"), script=dir.resolve("bootstrap ' $(bad).sh");
        String secret="secret' $(printf leaked) `printf leaked`;\nsecond";
        try {
            Files.writeString(script,"#!/bin/bash\nprintf '%s\\n' \"$OPERIT_RUNTIME_DIR\" \"$OPERIT_BRIDGE_URL\" \"$OPERIT_BRIDGE_TOKEN\" \"$1\"\n");
            HarnessSessionEnvironment.write(env.toFile(),"/private/runtime ' space","http://127.0.0.1:3222/mcp",secret);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE),Files.getPosixFilePermissions(env));
            for(String action:new String[]{"install","start","stop"}) {
                String command=HarnessSessionEnvironment.command(env.toString(),script.toString(),action);
                assertFalse("Provider debug logs and PTY input must contain no credential",command.contains(secret));
                assertFalse(command.contains("OPERIT_BRIDGE_TOKEN="));
                Process shell=new ProcessBuilder("/bin/bash","-c",command).start();
                assertEquals("/private/runtime ' space\nhttp://127.0.0.1:3222/mcp\n"+secret+"\n"+action+"\n",new String(shell.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
                assertEquals(0,shell.waitFor());
            }
        } finally { Files.deleteIfExists(env); Files.deleteIfExists(script); Files.deleteIfExists(dir); }
    }
    @Test public void arbitraryBootstrapActionsAreRejected() {
        try { HarnessSessionEnvironment.command("/env","/script","stop; printf injected"); fail("Unrecognized action accepted"); }
        catch(IllegalArgumentException expected) {}
    }
}
