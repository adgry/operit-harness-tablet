package com.ai.assistance.operit.harness;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class HarnessRpcTest {
    private HarnessRpc rpc() { return new HarnessRpc(new HarnessRpc.NativeTools() {
        public JSONArray catalog() throws Exception {return new JSONArray("[{\"name\":\"device_info\"}]");}
        public JSONObject execute(String name, JSONObject parameters) throws Exception {return new JSONObject().put("content",new JSONArray().put(new JSONObject().put("type","text").put("text","device result")));}
    }); }
    @Test public void initializationNegotiatesAndAdvertisesOnlyTools() throws Exception {
        JSONObject result=rpc().dispatch(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}")).getJSONObject("result");
        assertEquals("2025-03-26",result.getString("protocolVersion"));
        assertTrue(result.getJSONObject("capabilities").has("tools"));
        assertFalse(result.getJSONObject("capabilities").has("resources"));
    }
    @Test public void unknownMethodsAndToolsCannotExecute() throws Exception {
        assertEquals(-32601,rpc().dispatch(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"evil\"}")).getJSONObject("error").getInt("code"));
        JSONObject result=rpc().dispatch(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"operit_execute\",\"arguments\":{\"tool\":\"run_ui_subagent\",\"parameters\":{}}}}")).getJSONObject("result");
        assertTrue(result.getBoolean("isError"));
    }
    @Test public void catalogAndExecuteReturnRealContent() throws Exception {
        JSONObject result=rpc().dispatch(new JSONObject("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"operit_execute\",\"arguments\":{\"tool\":\"device_info\",\"parameters\":{}}}}")).getJSONObject("result");
        assertEquals("device result",result.getJSONArray("content").getJSONObject(0).getString("text"));
        assertNull(rpc().dispatch(new JSONObject("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
    }
}
