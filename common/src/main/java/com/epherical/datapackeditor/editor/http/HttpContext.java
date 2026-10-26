package com.epherical.datapackeditor.editor.http;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.editor.MemoryChecker;
import com.epherical.datapackeditor.editor.http.HttpRoutes.RequestException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpContext implements AutoCloseable {
    private final HttpExchange exchange;
    private final Map<String, String> pathParameters;
    private Map<String, String> queryParameters;
    private byte[] requestBody;
    private String requestText;
    private byte[] responseBytes;
    private InputStream responseStream;
    private OutputStream output;
    private int status = 200;
    private boolean committed;
    private boolean finished;

    public HttpContext(HttpExchange exchange, Map<String, String> pathParameters) {
        this.exchange = exchange;
        this.pathParameters = pathParameters;
        contentType(DatapackEditorConstants.HTTP_TEXT_UTF8);
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public String path() {
        return exchange.getRequestURI().getPath();
    }

    public String origin() {
        return DatapackEditorConstants.HTTP_ORIGIN_PREFIX + exchange.getLocalAddress().getHostString() + ":" + exchange.getLocalAddress().getPort();
    }

    public String requestHeader(String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    public String queryParam(String name) {
        if (queryParameters == null) {
            queryParameters = new LinkedHashMap<>();
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null) {
                for (String parameter : query.split("&")) {
                    int separator = parameter.indexOf('=');
                    String key = decode(separator < 0 ? parameter : parameter.substring(0, separator));
                    String value = separator < 0 ? "" : decode(parameter.substring(separator + 1));
                    queryParameters.putIfAbsent(key, value);
                }
            }
        }
        return queryParameters.get(name);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new RequestException(DatapackEditorConstants.HTTP_BAD_REQUEST, "Invalid query encoding");
        }
    }

    public String pathParam(String name) {
        String value = pathParameters.get(name);
        if (value == null) throw new IllegalArgumentException("Unknown path parameter: " + name);
        return value;
    }

    public byte[] bodyAsBytes() {
        if (requestBody == null) {
            try {
                requestBody = MemoryChecker.bytes(exchange.getRequestBody(),
                        DatapackEditorConstants.HTTP_REQUEST_MAX_BYTES, "HTTP request body");
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return requestBody;
    }

    public String body() {
        if (requestText == null) requestText = new String(bodyAsBytes(), StandardCharsets.UTF_8);
        return requestText;
    }

    public HttpContext status(int value) {
        requireUncommitted();
        status = value;
        return this;
    }

    public HttpContext header(String name, String value) {
        requireUncommitted();
        exchange.getResponseHeaders().set(name, value);
        return this;
    }

    public HttpContext contentType(String value) {
        return header("Content-Type", value);
    }

    public HttpContext result(String value) {
        return result(value.getBytes(StandardCharsets.UTF_8));
    }

    public HttpContext result(byte[] value) {
        requireUncommitted();
        clearResult();
        responseBytes = value;
        return this;
    }

    public HttpContext result(InputStream value) {
        requireUncommitted();
        clearResult();
        responseStream = value;
        return this;
    }

    public OutputStream outputStream() {
        if (output == null) {
            requireUncommitted();
            clearResult();
            output = new OutputStream() {
                @Override
                public void write(int value) throws IOException {
                    write(new byte[]{(byte) value});
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (finished) throw new IOException("Response is closed");
                    if (length == 0 || isHead()) return;
                    commit(0);
                    exchange.getResponseBody().write(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    if (committed) exchange.getResponseBody().flush();
                }

                @Override
                public void close() throws IOException {
                    finish();
                }
            };
        }
        return output;
    }

    public boolean isCommitted() {
        return committed;
    }

    private boolean isHead() {
        return method().equals(DatapackEditorConstants.HTTP_METHOD_HEAD);
    }

    private void requireUncommitted() {
        if (committed || finished) throw new IllegalStateException("Response is already committed");
    }

    private void commit(long length) throws IOException {
        if (!committed) {
            exchange.sendResponseHeaders(status, length);
            committed = true;
        }
    }

    public void finish() throws IOException {
        if (finished) return;
        try {
            if (!committed) {
                if (isHead() || status == 204 || status == 304) {
                    if (isHead() && responseBytes != null) {
                        header(DatapackEditorConstants.HTTP_CONTENT_LENGTH, Integer.toString(responseBytes.length));
                    }
                    commit(-1);
                } else if (responseBytes != null) {
                    commit(responseBytes.length == 0 ? -1 : responseBytes.length);
                    if (responseBytes.length > 0) exchange.getResponseBody().write(responseBytes);
                } else if (responseStream != null) {
                    commit(0);
                    responseStream.transferTo(exchange.getResponseBody());
                } else {
                    commit(-1);
                }
            }
        } finally {
            close();
        }
    }

    private void clearResult() {
        if (responseStream != null) {
            try {
                responseStream.close();
            } catch (IOException ignored) {
                // Closing a superseded result must not prevent an error response.
            }
            responseStream = null;
        }
        responseBytes = null;
    }

    @Override
    public void close() {
        finished = true;
        clearResult();
        exchange.close();
    }
}
