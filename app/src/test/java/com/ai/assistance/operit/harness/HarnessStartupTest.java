package com.ai.assistance.operit.harness;

import org.junit.Test;
import static org.junit.Assert.*;

public class HarnessStartupTest {
    @Test public void chineseSetupDiagnosticsLoseCsiColorsAndCursorControls() {
        assertEquals("准备环境", HarnessStartup.clean("\u001b[32m准备环境\u001b[0m"));
        assertEquals("正在解压 Ubuntu", HarnessStartup.clean("\u001b[1A\u001b[K\u001b[1A\u001b[K\u001b[31m正在解压 Ubuntu\u001b[0m"));
        assertEquals("下载完成", HarnessStartup.clean("下载\r\b完成\u0000\u0007"));
    }

    @Test public void titleAndHyperlinkEscapesNeverBecomeDiagnosticText() {
        assertEquals("Node", HarnessStartup.clean("\u001b]0;title\u0007Node"));
        assertEquals("详情", HarnessStartup.clean("\u001b]8;;https://example.test\u001b\\详情\u001b]8;;\u001b\\"));
        assertEquals("错误: 下载失败", HarnessStartup.clean("错误: 下载失败\u001b]0;incomplete title"));
        assertEquals("真实错误", HarnessStartup.clean("\u009b31m真实错误\u009b0m"));
    }

    @Test public void diagnosticsRemoveCredentialsAfterCleaningTerminalSequences() {
        assertEquals("认证失败 [redacted]", HarnessStartup.diagnostic("认证失败 \u001b[31mprivate-secret\u001b[0m", "private-secret"));
        assertEquals("dsh web: http://127.0.0.1:31337/?token=[redacted]", HarnessStartup.diagnostic("dsh web: http://127.0.0.1:31337/?token=web-secret", "private-secret"));
        assertEquals("http://127.0.0.1:31337/?x=1&token=[redacted]&next=2", HarnessStartup.diagnostic("http://127.0.0.1:31337/?x=1&token=web-secret&next=2", ""));
        assertEquals("Authorization: Bearer [redacted]", HarnessStartup.diagnostic("Authorization: Bearer another-secret", "private-secret"));
        assertEquals("真实错误", HarnessStartup.diagnostic("真实错误", ""));
    }

    @Test public void explicitBootstrapPhasesAdvanceTheDisplayedStage() {
        HarnessStartup.Stage stage = HarnessStartup.Stage.TERMINAL;
        stage = HarnessStartup.nextStage(stage, "operit-harness: phase=prerequisites");
        assertEquals(HarnessStartup.Stage.PREREQUISITES, stage);
        stage = HarnessStartup.nextStage(stage, "operit-harness: phase=node");
        assertEquals(HarnessStartup.Stage.NODE, stage);
        stage = HarnessStartup.nextStage(stage, "operit-harness: phase=engine");
        assertEquals(HarnessStartup.Stage.ENGINE, stage);
        stage = HarnessStartup.nextStage(stage, "operit-harness: phase=plugins");
        assertEquals(HarnessStartup.Stage.PLUGINS, stage);
        stage = HarnessStartup.nextStage(stage, "operit-harness: phase=start");
        assertEquals(HarnessStartup.Stage.STARTING, stage);
    }

    @Test public void ordinaryLogsAndUntrustedReadyTextCannotFinishStartup() {
        for (String line : new String[]{"installation complete", "Harness is running", "TERMINAL_READY", "operit-harness: phase=ready", "prefix operit-harness: phase=node", "operit-harness: phase=node extra", "dsh web: http://evil.test/?token=secret"}) {
            assertEquals(line, HarnessStartup.Stage.STARTING, HarnessStartup.nextStage(HarnessStartup.Stage.STARTING, line));
            assertNull(line, HarnessSecurity.launchUrl(line));
        }
        assertEquals(HarnessStartup.Stage.RUNNING, HarnessStartup.nextStage(HarnessStartup.Stage.RUNNING, "operit-harness: phase=node"));
        assertEquals("http://127.0.0.1:31337/?token=abc", HarnessSecurity.launchUrl(HarnessStartup.clean("\u001b[32mdsh web: http://127.0.0.1:31337/?token=abc\u001b[0m")));
    }
}
