package com.epherical.epherolib;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.data.ConditionalLootTableSubProvider;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(ModConstants.MOD_ID)
public class EpheroLibForge {

    private static EpheroLibForge mod;


    public EpheroLibForge() {
        mod = this;
        CommonPlatform.create(new ForgePlatform());

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

    @SubscribeEvent
    private void onCommandRegister(RegisterCommandsEvent event) {

    }


}
