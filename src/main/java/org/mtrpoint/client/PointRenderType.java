package org.mtrpoint.client;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.mtrpoint.PointMod;
import java.io.IOException;
import java.util.*;

@Mod.EventBusSubscriber(modid=PointMod.ID,bus=Mod.EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class PointRenderType extends RenderType {
    private static ShaderInstance shader;
    private static final Map<ResourceLocation,RenderType> TYPES=new HashMap<>();
    private PointRenderType(){super("point",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,256,false,false,()->{},()->{});}
    @SubscribeEvent public static void shaders(RegisterShadersEvent event)throws IOException{
        event.registerShader(new ShaderInstance(event.getResourceProvider(),new ResourceLocation(PointMod.ID,"point"),DefaultVertexFormat.NEW_ENTITY),s->shader=s);
    }
    public static RenderType texture(ResourceLocation texture){
        return TYPES.computeIfAbsent(texture,t->create("point",DefaultVertexFormat.NEW_ENTITY,VertexFormat.Mode.QUADS,256,true,false,
            CompositeState.builder().setShaderState(new ShaderStateShard(()->shader)).setTextureState(new TextureStateShard(t,false,false))
                .setTransparencyState(NO_TRANSPARENCY).setCullState(NO_CULL).setLightmapState(LIGHTMAP).setOverlayState(OVERLAY).createCompositeState(true)));
    }
}
