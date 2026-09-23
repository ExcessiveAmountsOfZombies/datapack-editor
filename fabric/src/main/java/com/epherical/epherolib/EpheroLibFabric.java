package com.epherical.epherolib;

import com.epherical.epherolib.editor.EditorCommands;
import com.epherical.epherolib.editor.EditorWebServer;
import com.epherical.epherolib.impl.schemas.ComponentSchemaRegister;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class EpheroLibFabric implements ModInitializer {


    @Override
    public void onInitialize() {
        CommonPlatform.create(new FabricPlatform());
        ComponentSchemaRegister.register();


        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                EditorCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EditorWebServer.stop());

    }
}
