package com.epherical.datapackeditor.editor.assets;

import com.epherical.datapackeditor.DatapackEditorConstants;
import com.epherical.datapackeditor.editor.http.HttpRoutes;
import com.epherical.datapackeditor.editor.http.HttpContext;

import java.io.InputStream;
import java.util.Locale;

public class EditorStaticFiles {

    public static void register(HttpRoutes routes) {
        if (EditorStaticFiles.class.getResource(DatapackEditorConstants.PATH_UI_RESOURCE_ROOT + DatapackEditorConstants.PATH_INDEX_HTML) == null) {
            throw new IllegalStateException("The bundled datapack-editor UI is missing index.html. "
                    + "Rebuild the mod with the buildEditorUi task enabled.");
        }

        routes.get(DatapackEditorConstants.PATH_SEPARATOR, context -> serve(context, DatapackEditorConstants.PATH_INDEX_HTML));
        routes.get("/index.html", context -> serve(context, DatapackEditorConstants.PATH_INDEX_HTML));
        routes.get("/assets/<path>", context -> serve(context, DatapackEditorConstants.PATH_ASSETS_PREFIX + context.pathParam(DatapackEditorConstants.JSON_PATH)));
    }

    private static void serve(HttpContext context, String path) {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            context.status(DatapackEditorConstants.HTTP_NOT_FOUND);
            return;
        }
        for (String segment : path.split(DatapackEditorConstants.PATH_SEPARATOR, -1)) {
            if (segment.isEmpty() || segment.equals(DatapackEditorConstants.PATH_CURRENT_DIRECTORY) || segment.equals(DatapackEditorConstants.PATH_PARENT_DIRECTORY)) {
                context.status(DatapackEditorConstants.HTTP_NOT_FOUND);
                return;
            }
        }

        InputStream resource = EditorStaticFiles.class.getResourceAsStream(DatapackEditorConstants.PATH_UI_RESOURCE_ROOT + path);
        if (resource == null) {
            context.status(DatapackEditorConstants.HTTP_NOT_FOUND);
            return;
        }

        String contentType = contentType(path);
        context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, "max-age=0")
                .contentType(contentType)
                .result(resource);
    }

    private static String contentType(String path) {
        String extension = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case DatapackEditorConstants.FILE_EXTENSION_JSON, "map" -> DatapackEditorConstants.HTTP_JSON;
            case "wasm" -> "application/wasm";
            case "zip" -> DatapackEditorConstants.HTTP_ZIP;
            case "ttf" -> "font/ttf";
            case "otf" -> "font/otf";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            case "png" -> "image/png";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            default -> DatapackEditorConstants.HTTP_OCTET_STREAM;
        };
    }
}
