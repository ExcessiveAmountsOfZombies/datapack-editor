package com.epherical.datapackeditor.editor.assets;

import com.epherical.datapackeditor.DatapackEditorConstants;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;

import java.io.InputStream;

public class EditorStaticFiles {

    public static void register(JavalinConfig config) {
        if (EditorStaticFiles.class.getResource(DatapackEditorConstants.PATH_UI_RESOURCE_ROOT + DatapackEditorConstants.PATH_INDEX_HTML) == null) {
            throw new IllegalStateException("The bundled datapack-editor UI is missing index.html. "
                    + "Rebuild the mod with the buildEditorUi task enabled.");
        }

        config.routes.get(DatapackEditorConstants.PATH_SEPARATOR, context -> serve(context, DatapackEditorConstants.PATH_INDEX_HTML));
        config.routes.get("/index.html", context -> serve(context, DatapackEditorConstants.PATH_INDEX_HTML));
        config.routes.get("/assets/<path>", context -> serve(context, DatapackEditorConstants.PATH_ASSETS_PREFIX + context.pathParam(DatapackEditorConstants.JSON_PATH)));
    }

    private static void serve(Context context, String path) {
        if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            context.status(HttpStatus.NOT_FOUND);
            return;
        }
        for (String segment : path.split(DatapackEditorConstants.PATH_SEPARATOR, -1)) {
            if (segment.isEmpty() || segment.equals(DatapackEditorConstants.PATH_CURRENT_DIRECTORY) || segment.equals(DatapackEditorConstants.PATH_PARENT_DIRECTORY)) {
                context.status(HttpStatus.NOT_FOUND);
                return;
            }
        }

        InputStream resource = EditorStaticFiles.class.getResourceAsStream(DatapackEditorConstants.PATH_UI_RESOURCE_ROOT + path);
        if (resource == null) {
            context.status(HttpStatus.NOT_FOUND);
            return;
        }

        String contentType = MimeTypes.DEFAULTS.getMimeByExtension(path);
        context.header(DatapackEditorConstants.HTTP_CACHE_CONTROL, "max-age=0")
                .contentType(contentType == null ? DatapackEditorConstants.HTTP_OCTET_STREAM : contentType)
                .result(resource);
    }
}
