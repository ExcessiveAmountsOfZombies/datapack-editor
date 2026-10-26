package com.epherical.datapackeditor.editor.http;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HttpRoutes {
    private static final Logger LOGGER = System.getLogger(HttpRoutes.class.getName());
    private final List<Route> routes = new ArrayList<>();
    private final List<Before> before = new ArrayList<>();
    private final List<Filter> filters = new ArrayList<>();
    private final Map<Class<?>, ExceptionHandler<?>> exceptions = new LinkedHashMap<>();
    private boolean frozen;

    @FunctionalInterface
    public interface Handler {
        void handle(HttpContext context) throws Exception;
    }

    @FunctionalInterface
    public interface FilterHandler {
        void handle(HttpContext context, Handler next) throws Exception;
    }

    @FunctionalInterface
    public interface ExceptionHandler<E extends Exception> {
        void handle(E exception, HttpContext context) throws Exception;
    }

    public HttpRoutes get(String path, Handler handler) {
        return add(DatapackEditorConstants.HTTP_METHOD_GET, path, handler);
    }

    public HttpRoutes post(String path, Handler handler) {
        return add("POST", path, handler);
    }

    public HttpRoutes put(String path, Handler handler) {
        return add("PUT", path, handler);
    }

    public HttpRoutes delete(String path, Handler handler) {
        return add("DELETE", path, handler);
    }

    private HttpRoutes add(String method, String path, Handler handler) {
        requireMutable();
        validatePattern(path);
        if (routes.stream().anyMatch(route -> route.method.equals(method) && route.pattern.equals(path))) {
            throw new IllegalArgumentException("Duplicate route: " + method + " " + path);
        }
        routes.add(new Route(method, path, handler));
        return this;
    }

    public HttpRoutes before(Handler handler) {
        return before(DatapackEditorConstants.PATH_SEPARATOR, handler);
    }

    public HttpRoutes before(String path, Handler handler) {
        requireMutable();
        before.add(new Before(path, handler));
        return this;
    }

    public HttpRoutes filter(String path, FilterHandler handler) {
        requireMutable();
        filters.add(new Filter(path, handler));
        return this;
    }

    public <E extends Exception> HttpRoutes exception(Class<E> type, ExceptionHandler<E> handler) {
        requireMutable();
        exceptions.put(type, handler);
        return this;
    }

    public void freeze() {
        frozen = true;
    }

    private void requireMutable() {
        if (frozen) throw new IllegalStateException("Register routes before starting the server");
    }

    private static void validatePattern(String pattern) {
        if (!pattern.startsWith(DatapackEditorConstants.PATH_SEPARATOR)) {
            throw new IllegalArgumentException("Route paths must start with /: " + pattern);
        }
        int capture = pattern.indexOf('<');
        if (capture >= 0 && (!pattern.endsWith(DatapackEditorConstants.HTTP_CAPTURE_END) || capture != pattern.lastIndexOf('<')
                || capture == 0 || pattern.charAt(capture - 1) != DatapackEditorConstants.PATH_SEPARATOR_CHAR
                || capture + 2 == pattern.length())) {
            throw new IllegalArgumentException("Only a named trailing path capture is supported: " + pattern);
        }
    }

    private static Map<String, String> match(String pattern, String path) {
        int capture = pattern.indexOf('<');
        if (capture < 0) return pattern.equals(path) ? Map.of() : null;
        String prefix = pattern.substring(0, capture);
        if (!path.startsWith(prefix) || path.length() == prefix.length()) return null;
        return Map.of(pattern.substring(capture + 1, pattern.length() - DatapackEditorConstants.HTTP_CAPTURE_END.length()), path.substring(prefix.length()));
    }

    private static boolean matchesFilter(String pattern, String path) {
        if (pattern.equals(DatapackEditorConstants.PATH_SEPARATOR)) return true;
        return pattern.endsWith("/*") ? path.startsWith(pattern.substring(0, pattern.length() - "*".length()))
                : pattern.equals(path);
    }

    public void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        Route selected = null;
        Map<String, String> parameters = Map.of();
        Set<String> allowed = new LinkedHashSet<>();
        for (Route route : routes) {
            Map<String, String> candidate = match(route.pattern, path);
            if (candidate == null) continue;
            allowed.add(route.method);
            if (route.method.equals(DatapackEditorConstants.HTTP_METHOD_GET)) allowed.add(DatapackEditorConstants.HTTP_METHOD_HEAD);
            if (selected == null && (route.method.equals(method)
                    || method.equals(DatapackEditorConstants.HTTP_METHOD_HEAD) && route.method.equals(DatapackEditorConstants.HTTP_METHOD_GET))) {
                selected = route;
                parameters = candidate;
            }
        }
        Route target = selected;
        try (HttpContext context = new HttpContext(exchange, parameters)) {
            Handler chain = request -> dispatch(request, target, allowed);
            for (Filter filter : filters.reversed()) {
                if (!matchesFilter(filter.pattern, path)) continue;
                Handler next = chain;
                chain = request -> filter.handler.handle(request, next);
            }
            try {
                chain.handle(context);
            } catch (Exception exception) {
                respondToException(exception, context);
            }
            context.finish();
        } catch (IOException exception) {
            LOGGER.log(Level.DEBUG, "Editor HTTP response closed", exception);
        }
    }

    private void dispatch(HttpContext context, Route target, Set<String> allowed) throws IOException {
        try {
            for (Before entry : before) {
                if (matchesFilter(entry.pattern, context.path())) entry.handler.handle(context);
            }
            if (target != null) {
                target.handler.handle(context);
            } else if (allowed.isEmpty()) {
                context.status(DatapackEditorConstants.HTTP_NOT_FOUND).result("Not found");
            } else {
                context.status(405).header("Allow", String.join(", ", allowed)).result("Method not allowed");
            }
        } catch (Exception exception) {
            respondToException(exception, context);
        }
        context.finish();
    }

    @SuppressWarnings("unchecked")
    private void respondToException(Exception exception, HttpContext context) {
        if (context.isCommitted()) {
            LOGGER.log(Level.DEBUG, "Editor HTTP stream interrupted", exception);
            return;
        }
        try {
            for (Class<?> type = exception.getClass(); type != null; type = type.getSuperclass()) {
                ExceptionHandler<Exception> handler = (ExceptionHandler<Exception>) exceptions.get(type);
                if (handler != null) {
                    handler.handle(exception, context);
                    return;
                }
            }
            if (exception instanceof RequestException request) {
                context.status(request.status).contentType(DatapackEditorConstants.HTTP_TEXT_UTF8).result(request.getMessage());
                return;
            }
        } catch (Exception handlerException) {
            exception.addSuppressed(handlerException);
        }
        LOGGER.log(Level.ERROR, "Editor HTTP handler failed", exception);
        context.status(DatapackEditorConstants.HTTP_INTERNAL_SERVER_ERROR)
                .contentType(DatapackEditorConstants.HTTP_TEXT_UTF8).result("Internal server error");
    }

    private record Route(String method, String pattern, Handler handler) {}
    private record Before(String pattern, Handler handler) {}
    private record Filter(String pattern, FilterHandler handler) {}

    public static class RequestException extends RuntimeException {
        private final int status;

        public RequestException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
