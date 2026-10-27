package com.ai.assistance.operit.harness;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Boundaries shared by WebView, runtime stdout and the authenticated native MCP server. */
public final class HarnessSecurity {
    private HarnessSecurity() {}
    public static final int MAX_BODY = 1024 * 1024;
    public static final Set<String> VERSIONS = new HashSet<>(Arrays.asList("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25"));
    private static final Set<String> TOOLS = new HashSet<>(Arrays.asList(
        "device_info", "get_page_info", "capture_screenshot", "tap", "click_element", "long_press", "set_input_text", "press_key", "swipe",
        "list_files", "read_file", "read_file_part", "read_file_full", "write_file", "delete_file", "file_exists", "move_file", "copy_file", "make_directory", "find_files", "file_info", "grep_code", "grep_context",
        "query_memory", "get_memory_by_title", "create_memory", "update_memory", "delete_memory", "move_memory", "query_memory_links", "update_memory_link", "delete_memory_link",
        "get_all_workflows", "get_workflow",
        "create_terminal_session", "execute_in_terminal_session", "execute_hidden_terminal_command", "close_terminal_session", "input_in_terminal_session", "get_terminal_session_screen",
        "browser_navigate", "browser_navigate_back", "browser_click", "browser_type", "browser_wait_for", "browser_snapshot", "browser_take_screenshot", "browser_evaluate", "browser_close", "browser_console_messages", "browser_fill_form", "browser_handle_dialog", "browser_hover", "browser_press_key", "browser_select_option", "browser_tabs",
        "list_installed_apps", "start_app", "get_notifications", "get_system_setting"
    ));
    public static String launchUrl(String line) {
        if (!line.startsWith("dsh web: ")) return null;
        String url = line.substring(9).trim();
        try {
            URI uri = new URI(url);
            if (!local(uri) || !"/".equals(uri.getRawPath()) || uri.getRawFragment()!=null) return null;
            if (uri.getRawQuery()==null || !uri.getRawQuery().matches("token=[A-Za-z0-9._~%+-]{1,512}")) return null;
            return url;
        } catch (Exception e) { return null; }
    }
    private static boolean local(URI uri) {
        return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getRawUserInfo()==null && uri.getPort()>0 && uri.getPort()<=65535;
    }
    public static boolean sameOrigin(String launch, String target) {
        try { URI a=new URI(launch), b=new URI(target); return local(a) && local(b) && a.getPort()==b.getPort(); }
        catch (Exception e) { return false; }
    }
    public static int httpStatus(String method,String path,String auth,String origin,String version,String token,int port,long size) {
        if (!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8), (auth==null?"":auth).getBytes(StandardCharsets.UTF_8))) return 401;
        if (!"/mcp".equals(path)) return 404;
        if (origin!=null && !origin.equals("http://127.0.0.1:"+port)) return 403;
        if (version!=null && !VERSIONS.contains(version)) return 400;
        if (!"POST".equals(method)) return 405;
        if (size<0 || size>MAX_BODY) return 413;
        return 200;
    }
    public static boolean allowedMethod(String method) { return Arrays.asList("initialize","notifications/initialized","tools/list","tools/call","ping").contains(method); }
    public static boolean allowedTool(String name) { return TOOLS.contains(name); }
    public static boolean nativeControl(String launch, String current, String target) {
        return launch != null && current != null && launchUrl("dsh web: " + launch) != null
            && sameOrigin(launch, current) && "operit-harness://device".equals(target);
    }
    public static String quote(String value) { if (value.indexOf('\0')>=0) throw new IllegalArgumentException("NUL in shell argument"); return "'"+value.replace("'", "'\"'\"'")+"'"; }
}
