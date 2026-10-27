package com.ai.assistance.operit.harness;
import org.json.*;

/** Stateless MCP JSON-RPC. Native execution has a second allowlist at the device boundary. */
public final class HarnessRpc {
    public interface NativeTools { JSONArray catalog() throws Exception; JSONObject execute(String name, JSONObject parameters) throws Exception; }
    private final NativeTools tools;
    public HarnessRpc(NativeTools tools) { this.tools=tools; }
    public JSONObject dispatch(JSONObject request) throws Exception {
        Object id=request.opt("id");
        if (!"2.0".equals(request.optString("jsonrpc")) || !request.has("method")) return error(id,-32600,"Invalid JSON-RPC request");
        String method=request.getString("method");
        if (!HarnessSecurity.allowedMethod(method)) return error(id,-32601,"Method not found");
        if (method.equals("notifications/initialized") && id==null) return null;
        if (id==null) return error(null,-32600,"Request id required");
        JSONObject result;
        JSONObject params=request.optJSONObject("params");
        switch(method) {
        case "initialize":
            String version=params==null?"":params.optString("protocolVersion");
            if (!HarnessSecurity.VERSIONS.contains(version)) version="2025-03-26";
            result=new JSONObject().put("protocolVersion",version).put("capabilities",new JSONObject().put("tools",new JSONObject().put("listChanged",false)))
                .put("serverInfo",new JSONObject().put("name","operit-native").put("version","1.0.0")); break;
        case "ping": result=new JSONObject(); break;
        case "tools/list":
            JSONArray exported=new JSONArray();
            exported.put(new JSONObject().put("name","operit_catalog").put("description","List native Android operations and their documented parameter schemas. Excludes agent, model, voice and plugin activation tools.").put("inputSchema",new JSONObject("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")));
            exported.put(new JSONObject().put("name","operit_execute").put("description","Execute an allowlisted Android operation after native permission approval. Use operit_catalog for operation names and parameter schemas.").put("inputSchema",new JSONObject("{\"type\":\"object\",\"properties\":{\"tool\":{\"type\":\"string\"},\"parameters\":{\"type\":\"object\"}},\"required\":[\"tool\",\"parameters\"],\"additionalProperties\":false}")));
            result=new JSONObject().put("tools",exported); break;
        case "tools/call":
            if(params==null) return error(id,-32602,"Missing tool parameters");
            String name=params.optString("name");
            if(name.equals("operit_catalog")) result=text(tools.catalog().toString(),false);
            else if(name.equals("operit_execute")) {
                JSONObject arguments=params.optJSONObject("arguments");
                if(arguments==null || !HarnessSecurity.allowedTool(arguments.optString("tool"))) result=text("Native operation is not allowlisted",true);
                else if(arguments.optJSONObject("parameters")==null) result=text("parameters must be an object",true);
                else { try { result=tools.execute(arguments.getString("tool"),arguments.getJSONObject("parameters")); }
                    catch(Exception e) { result=text("Native execution failed: "+e.getMessage(),true); } }
            } else result=text("Unknown MCP tool",true);
            break;
        default: return error(id,-32600,"Invalid request");
        }
        return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",result);
    }
    public static JSONObject text(String value,boolean isError) throws Exception {
        return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",value))).put("isError",isError);
    }
    public static JSONObject error(Object id,int code,String message) throws Exception {
        return new JSONObject().put("jsonrpc","2.0").put("id",id==null?JSONObject.NULL:id).put("error",new JSONObject().put("code",code).put("message",message));
    }
}
