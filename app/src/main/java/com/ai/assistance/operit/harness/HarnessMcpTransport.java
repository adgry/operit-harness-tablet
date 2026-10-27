package com.ai.assistance.operit.harness;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Android-independent HTTP boundary shared by the native server and real-socket tests. */
public abstract class HarnessMcpTransport extends NanoHTTPD {
    private final String token;

    protected HarnessMcpTransport(String token) {
        super("127.0.0.1", 0);
        this.token = token;
    }

    protected abstract JSONObject dispatch(JSONObject request) throws Exception;

    @Override public Response serve(IHTTPSession session) {
        long size;
        try { size = Long.parseLong(session.getHeaders().getOrDefault("content-length", "-1")); }
        catch (NumberFormatException e) { size = -1; }
        int status = HarnessSecurity.httpStatus(session.getMethod().name(), session.getUri(),
            session.getHeaders().get("authorization"), session.getHeaders().get("origin"),
            session.getHeaders().get("mcp-protocol-version"), token, getListeningPort(), size);
        if (status != 200) return reject(status, "Request rejected");
        if (session.getHeaders().get("transfer-encoding") != null) return reject(400, "Chunked requests are unsupported");
        String contentType = session.getHeaders().get("content-type");
        if (contentType == null || !"application/json".equals(contentType.split(";", 2)[0].trim())) {
            return reject(415, "application/json required");
        }
        try {
            byte[] data = new byte[(int) size];
            int offset = 0;
            while (offset < data.length) {
                int count = session.getInputStream().read(data, offset, data.length - offset);
                if (count < 0) return reject(400, "Incomplete request");
                offset += count;
            }
            JSONObject result = dispatch(new JSONObject(new String(data, StandardCharsets.UTF_8)));
            return result == null ? newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
                : newFixedLengthResponse(Response.Status.OK, "application/json", result.toString());
        } catch (Exception e) {
            Response response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Invalid JSON-RPC payload\"}}");
            response.closeConnection(true);
            return response;
        }
    }

    private Response reject(int status, String message) {
        Response response = newFixedLengthResponse(Response.Status.lookup(status), "text/plain", message);
        if (status == 401) response.addHeader("WWW-Authenticate", "Bearer");
        // An unread body must never be parsed as the next keep-alive HTTP request.
        // Do not drain untrusted oversized or chunked bodies just to reuse the socket.
        response.closeConnection(true);
        return response;
    }
}
