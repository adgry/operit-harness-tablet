package com.ai.assistance.operit.harness;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;

public class HarnessSecurityTest {
    @Test public void launchRequiresGenuineMarkerExactLoopbackAndToken() {
        assertEquals("http://127.0.0.1:31337/?token=abc_123", HarnessSecurity.launchUrl("dsh web: http://127.0.0.1:31337/?token=abc_123"));
        for (String invalid : new String[]{"http://127.0.0.1:31337/?token=x", "dsh web: http://localhost:31337/?token=x", "dsh web: http://127.0.0.1.evil:31337/?token=x", "dsh web: http://evil@127.0.0.1:31337/?token=x", "dsh web: http://127.0.0.1:31337/", "dsh web: https://127.0.0.1:31337/?token=x", "dsh web: http://127.0.0.1:31337/?token=x&token=y"}) {
            assertNull(invalid, HarnessSecurity.launchUrl(invalid));
        }
    }
    @Test public void navigationRequiresExactLaunchOrigin() {
        String launch="http://127.0.0.1:31337/?token=abc";
        assertTrue(HarnessSecurity.sameOrigin(launch,"http://127.0.0.1:31337/settings"));
        for(String url: new String[]{"http://127.0.0.1:31338/","http://localhost:31337/","http://u@127.0.0.1:31337/","file:///etc/passwd","javascript:alert(1)"}) assertFalse(url,HarnessSecurity.sameOrigin(launch,url));
    }
    @Test public void rejectsUnauthorizedOriginVersionsMethodsAndLegacyTools() {
        assertEquals(401,HarnessSecurity.httpStatus("POST","/mcp","Bearer wrong",null,null,"secret",31000,12));
        assertEquals(403,HarnessSecurity.httpStatus("POST","/mcp","Bearer secret","https://evil.test",null,"secret",31000,12));
        assertEquals(400,HarnessSecurity.httpStatus("POST","/mcp","Bearer secret",null,"1900-01-01","secret",31000,12));
        assertEquals(413,HarnessSecurity.httpStatus("POST","/mcp","Bearer secret",null,null,"secret",31000,1048577));
        assertEquals(405,HarnessSecurity.httpStatus("GET","/mcp","Bearer secret",null,null,"secret",31000,0));
        assertEquals(200,HarnessSecurity.httpStatus("POST","/mcp","Bearer secret","http://127.0.0.1:31000","2025-03-26","secret",31000,12));
        assertFalse(HarnessSecurity.allowedMethod("resources/list"));
        assertTrue(HarnessSecurity.allowedMethod("tools/call"));
        for(String tool:new String[]{"run_ui_subagent","call_chat_model","send_message_to_ai","plugin:execute","activate_package"}) assertFalse(HarnessSecurity.allowedTool(tool));
        assertTrue(HarnessSecurity.allowedTool("capture_screenshot"));
    }
    @Test public void workflowExecutionAndMutationCannotEnterLegacyExecutor() {
        assertTrue(HarnessSecurity.allowedTool("get_all_workflows"));
        assertTrue(HarnessSecurity.allowedTool("get_workflow"));
        for (String tool : new String[]{"create_workflow", "update_workflow", "patch_workflow", "enable_workflow", "disable_workflow", "delete_workflow", "trigger_workflow"}) {
            assertFalse(tool, HarnessSecurity.allowedTool(tool));
        }
    }
    @Test public void nativeControlsRequireExactRouteAndCurrentlyLoadedLocalPage() {
        String launch="http://127.0.0.1:31337/?token=abc";
        assertTrue(HarnessSecurity.nativeControl(launch, "http://127.0.0.1:31337/settings", "operit-harness://device"));
        for (String route : new String[]{"operit-harness://evil", "operit-harness://device/", "operit-harness://device/path", "operit-harness://device?command=stop", "operit-harness://device#stop", "operit-harness://u@device", "intent://device", "https://device"}) {
            assertFalse(route, HarnessSecurity.nativeControl(launch, launch, route));
        }
        assertFalse(HarnessSecurity.nativeControl(launch, "https://evil.test", "operit-harness://device"));
        assertFalse(HarnessSecurity.nativeControl(null, launch, "operit-harness://device"));
        assertFalse(HarnessSecurity.nativeControl(launch, null, "operit-harness://device"));
    }
    @Test public void shellQuotePreservesLiteralMetacharacters() throws Exception {
        String value="a'b $(printf injected) `printf injected` ; &\nnext";
        Process p=new ProcessBuilder("/bin/bash","-c","printf %s "+HarnessSecurity.quote(value)).start();
        assertEquals(value,new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        assertEquals(0,p.waitFor());
    }
}
