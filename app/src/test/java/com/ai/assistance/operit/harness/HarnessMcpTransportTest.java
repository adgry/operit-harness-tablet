package com.ai.assistance.operit.harness;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Real sockets: unread rejected bodies must never become the next HTTP request. */
public class HarnessMcpTransportTest {
    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";

    private HarnessMcpTransport start() throws IOException {
        HarnessMcpTransport server = new HarnessMcpTransport("secret") {
            private final HarnessRpc rpc = new HarnessRpc(new HarnessRpc.NativeTools() {
                public JSONArray catalog() { return new JSONArray(); }
                public JSONObject execute(String name, JSONObject parameters) { throw new AssertionError("Ping must not execute native tools"); }
            });
            @Override protected JSONObject dispatch(JSONObject request) throws Exception {
                return rpc.dispatch(request);
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        return server;
    }

    @Test public void unauthorizedBodyClosesSocketAndFreshAuthenticatedRequestSucceeds() throws Exception {
        HarnessMcpTransport server = start();
        try {
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", "", PING.length(), PING, 401);
            try (Socket socket = connect(server)) {
                send(socket, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + PING.length() + "\r\n\r\n" + PING);
                Reply reply = read(socket);
                assertEquals(200, reply.status);
                assertEquals(7, new JSONObject(reply.body).getInt("id"));
                // Successful bounded requests retain keep-alive and parse a second JSON body normally.
                send(socket, "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + PING.length() + "\r\n\r\n" + PING);
                assertEquals(200, read(socket).status);
            }
        } finally { server.stop(); }
    }

    @Test public void allEarlyRejectionsCloseWithoutWaitingForUnsafeBody() throws Exception {
        HarnessMcpTransport server = start();
        try {
            String auth = "Authorization: Bearer secret\r\n";
            assertRejected(server, "POST /wrong HTTP/1.1\r\n", auth, 12, "", 404);
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", auth + "Origin: https://remote.invalid\r\n", 12, "", 403);
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", auth + "MCP-Protocol-Version: invalid\r\n", 12, "", 400);
            assertRejected(server, "GET /mcp HTTP/1.1\r\n", auth, 12, "", 405);
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", auth, 1048577, "", 413);
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", auth + "Transfer-Encoding: chunked\r\n", 12, "", 400);
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", auth + "Content-Type: text/plain\r\n", 12, "", 415);
        } finally { server.stop(); }
    }

    @Test public void malformedJsonClosesConnection() throws Exception {
        HarnessMcpTransport server = start();
        try {
            assertRejected(server, "POST /mcp HTTP/1.1\r\n", "Authorization: Bearer secret\r\n", 1, "{", 400);
        } finally { server.stop(); }
    }

    private void assertRejected(HarnessMcpTransport server, String line, String headers, int size, String body, int status) throws Exception {
        try (Socket socket = connect(server)) {
            send(socket, line + "Host: 127.0.0.1\r\nConnection: keep-alive\r\n" + headers
                + (headers.contains("Content-Type:") ? "" : "Content-Type: application/json\r\n")
                + "Content-Length: " + size + "\r\n\r\n" + body);
            Reply reply = read(socket);
            assertEquals(status, reply.status);
            assertTrue("Rejected request must advertise connection close: " + status, reply.close);
            assertEquals("Rejected request must actually close the socket: " + status, -1, socket.getInputStream().read());
        }
    }
    private Socket connect(HarnessMcpTransport server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getListeningPort());
        socket.setSoTimeout(2000);
        return socket;
    }
    private void send(Socket socket, String request) throws IOException {
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
    private Reply read(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        String statusLine = line(input);
        assertNotNull("Expected HTTP response", statusLine);
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        int length = 0;
        boolean close = false;
        for (String header; (header = line(input)) != null && !header.isEmpty();) {
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
            if (lower.startsWith("connection:") && lower.contains("close")) close = true;
        }
        byte[] bytes = input.readNBytes(length);
        assertEquals(length, bytes.length);
        return new Reply(status, close, new String(bytes, StandardCharsets.UTF_8));
    }
    private String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).replace("\r", "");
            bytes.write(value);
        }
        return null;
    }
    private record Reply(int status, boolean close, String body) {}
}
