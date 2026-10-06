package org.mtrpoint.mixin;

import org.mtr.core.data.Rail;
import net.minecraft.resources.ResourceLocation;
import org.mtr.mapping.holder.*;
import org.mtr.mod.render.RenderRails;
import org.mtr.mod.resource.RailResource;
import org.mtrpoint.client.*;
import org.mtrpoint.geometry.V3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels only the appearance callbacks; signals, arrows, RailMath and map renderers are untouched. */
@Mixin(value=RenderRails.class,remap=false)
public abstract class RailRenderMixin {
    private static final ResourceLocation VANILLA_RAIL_TEXTURE=new ResourceLocation("minecraft","textures/block/rail.png");
    private static final ThreadLocal<Rail> point$current=new ThreadLocal<>();
    @Inject(method="render",at=@At("HEAD")) private static void point$frame(CallbackInfo ci){PointRenderer.beginFrame();}
    @Inject(method="renderRailStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;FLorg/mtr/mod/render/RenderRails$RenderState;FLorg/mtr/mapping/holder/Identifier;FFFF)V",at=@At("HEAD"))
    private static void point$begin(ClientWorld world,Rail rail,float y,@Coerce Object state,float width,Identifier texture,float u1,float v1,float u2,float v2,CallbackInfo ci){point$current.set(rail);SleeperSeams.begin();}
    @Inject(method="renderRailStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;FLorg/mtr/mod/render/RenderRails$RenderState;FLorg/mtr/mapping/holder/Identifier;FFFF)V",at=@At("RETURN"))
    private static void point$end(CallbackInfo ci){point$current.remove();}
    @Inject(method="lambda$renderRailStandard$16",at=@At("HEAD"),cancellable=true)
    private static void point$model(ClientWorld world,RailResource resource,boolean flip,boolean[] types,BlockPos pos,double x1,double z1,double x2,double z2,double x3,double z3,double x4,double z4,double y1,double y2,CallbackInfo ci){
        V3 a=new V3(x1,y1,z1),b=new V3(x3,y2,z3);
        if(PointClient.suppress(point$current.get(),resource.getId(),a.lerp(b,.5),0)){PointRenderer.preserve(point$current.get(),resource,flip,a,b);types[1]=true;ci.cancel();}
        else if(SleeperSeams.render(point$current.get(),resource,flip,a,b)){types[1]=true;ci.cancel();}
        else if(RailLod.render(point$current.get(),resource,flip,a,b)){types[1]=true;ci.cancel();}
    }
    @Inject(method="lambda$renderRailStandard$19",at=@At("HEAD"),cancellable=true)
    private static void point$flat(@Coerce Object state,ClientWorld world,Identifier texture,float y,float u1,float v1,float u2,float v2,int color,BlockPos pos,double x1,double z1,double x2,double z2,double x3,double z3,double x4,double z4,double y1,double y2,CallbackInfo ci){
        // The vanilla rail texture is appearance. Preview, signal colour and one-way overlays remain native.
        if(texture.data.equals(VANILLA_RAIL_TEXTURE)&&PointClient.suppress(point$current.get(),"default",new V3((x1+x2+x3+x4)/4,(y1+y2)/2,(z1+z2+z3+z4)/4),0))ci.cancel();
    }
    @Inject(method="render",at=@At("RETURN")) private static void point$draw(CallbackInfo ci){point$current.remove();PointRenderer.render();}
}
