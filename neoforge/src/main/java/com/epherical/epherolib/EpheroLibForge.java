package com.epherical.epherolib;

import com.epherical.epherolib.editor.EditorCommands;
import com.epherical.epherolib.editor.EditorWebServer;
import com.epherical.epherolib.impl.schemas.ComponentSchemaRegister;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod(ModConstants.MOD_ID)
public class EpheroLibForge {

    private static EpheroLibForge mod;


    public EpheroLibForge() {
        mod = this;
        CommonPlatform.create(new ForgePlatform());
        ComponentSchemaRegister.register();
        NeoForge.EVENT_BUS.addListener(this::onCommandRegister);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        //FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientInit);
        //FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonInit);
        //MinecraftForge.EVENT_BUS.register(this);
    }

    private void clientInit(FMLClientSetupEvent event) {
        //DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> AModClient::initClient);
        //MinecraftForge.EVENT_BUS.register(new AModClient());
    }

    private void commonInit(FMLCommonSetupEvent event) {

    }

    private void onCommandRegister(RegisterCommandsEvent event) {
        EditorCommands.register(event.getDispatcher());
    }

    private void onServerStopping(ServerStoppingEvent event) {
        EditorWebServer.stop();
    }

}
