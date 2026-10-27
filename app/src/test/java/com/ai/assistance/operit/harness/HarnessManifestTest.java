package com.ai.assistance.operit.harness;

import org.junit.Test;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;
import static org.junit.Assert.*;

/** Checks Android's declared component/intent policies, not source-code text. */
public class HarnessManifestTest {
    private static final String ANDROID="http://schemas.android.com/apk/res/android";
    @Test public void notificationListenerCanBeDiscoveredAndBoundOnlyBySystem() throws Exception {
        Path path=Paths.get("app/src/main/AndroidManifest.xml");
        if (!Files.exists(path)) path=Paths.get("src/main/AndroidManifest.xml");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        NodeList services=factory.newDocumentBuilder().parse(path.toFile()).getElementsByTagName("service");
        for(int i=0;i<services.getLength();i++) {
            Element service=(Element)services.item(i);
            if(!".services.notification.OperitNotificationListenerService".equals(service.getAttributeNS(ANDROID,"name"))) continue;
            assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",service.getAttributeNS(ANDROID,"permission"));
            assertEquals("true",service.getAttributeNS(ANDROID,"exported"));
            NodeList actions=service.getElementsByTagName("action");
            for(int j=0;j<actions.getLength();j++) {
                if("android.service.notification.NotificationListenerService".equals(((Element)actions.item(j)).getAttributeNS(ANDROID,"name"))) return;
            }
            fail("Native notification listener must advertise Android discovery action");
        }
        fail("Native notification listener missing");
    }
    @Test public void legacyCoreCannotResolveExternalOrExplicitLaunches() throws Exception {
        Path manifest=Paths.get("app/src/main/AndroidManifest.xml");
        if (!Files.exists(manifest)) manifest=Paths.get("src/main/AndroidManifest.xml");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc=factory.newDocumentBuilder().parse(manifest.toFile());
        NodeList activities=doc.getElementsByTagName("activity");
        boolean legacyFound=false, harnessFound=false;
        for(int i=0;i<activities.getLength();i++) {
            Element activity=(Element)activities.item(i);
            String name=activity.getAttributeNS(ANDROID,"name");
            if(name.equals(".ui.main.MainActivity")) {
                legacyFound=true;
                assertEquals("Legacy Activity must reject explicit component launch", "false", activity.getAttributeNS(ANDROID,"enabled"));
                assertEquals("false", activity.getAttributeNS(ANDROID,"exported"));
                assertEquals("Legacy intents must not resolve",0,activity.getElementsByTagName("intent-filter").getLength());
                assertEquals("Legacy shortcuts must not publish routes",0,activity.getElementsByTagName("meta-data").getLength());
            }
            if(name.equals(".harness.HarnessActivity")) {
                harnessFound=true;
                assertEquals("true",activity.getAttributeNS(ANDROID,"exported"));
                boolean launcher=false;
                NodeList categories=activity.getElementsByTagName("category");
                for(int j=0;j<categories.getLength();j++) if(((Element)categories.item(j)).getAttributeNS(ANDROID,"name").equals("android.intent.category.LAUNCHER")) launcher=true;
                assertTrue("Harness remains launchable",launcher);
            }
        }
        assertTrue(legacyFound);
        assertTrue(harnessFound);
        String[] disabled = {
            ".services.assistant.OperitAssistActivity", ".services.FloatingChatService", ".api.chat.AIForegroundService",
            ".integrations.tasker.ActivityConfigAIAgentAction", ".integrations.intent.ExternalChatReceiver",
            ".services.assistant.OperitVoiceInteractionService", ".services.assistant.OperitVoiceInteractionSessionService",
            ".widget.VoiceAssistantWidgetReceiver", ".widget.ToolPkgDesktopWidgetReceiver", ".widget.ToolPkgDesktopWidgetConfigActivity",
            ".integrations.tasker.WorkflowTaskerActivityConfig", ".integrations.tasker.WorkflowTaskerReceiver", ".integrations.tasker.WorkflowBootReceiver",
            ".core.tools.javascript.ScriptExecutionReceiver", ".core.tools.packTool.ToolPkgDebugInstallReceiver",
            ".core.tools.packTool.PackageDebugRefreshReceiver", ".core.tools.packTool.ToolPkgComposeDslDebugDumpReceiver"
        };
        NodeList components=doc.getElementsByTagName("*");
        for(String blocked:disabled) {
            boolean found=false;
            for(int i=0;i<components.getLength();i++) {
                Element component=(Element)components.item(i);
                if(!blocked.equals(component.getAttributeNS(ANDROID,"name"))) continue;
                found=true;
                assertEquals(blocked,"false",component.getAttributeNS(ANDROID,"enabled"));
                assertEquals(blocked,"false",component.getAttributeNS(ANDROID,"exported"));
                assertEquals(blocked,0,component.getElementsByTagName("intent-filter").getLength());
            }
            assertTrue(blocked,found);
        }
        for(String nativeComponent: new String[]{".core.tools.system.ScreenCaptureActivity", ".core.tools.system.ScreenCaptureService", ".core.tools.agent.ShowerBinderReceiver", ".services.notification.OperitNotificationListenerService", "rikka.shizuku.ShizukuProvider"}) {
            boolean available=false;
            for(int i=0;i<components.getLength();i++) {
                Element component=(Element)components.item(i);
                if(nativeComponent.equals(component.getAttributeNS(ANDROID,"name"))) available=!"false".equals(component.getAttributeNS(ANDROID,"enabled"));
            }
            assertTrue("Retain reusable native component: "+nativeComponent,available);
        }
    }
}
