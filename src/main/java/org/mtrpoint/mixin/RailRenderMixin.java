package org.mtrpoint.mixin;

import org.mtr.core.data.Rail;
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
    private static final ThreadLocal<Rail> point$current=new ThreadLocal<>();
    @Inject(method="renderRailStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;FLorg/mtr/mod/render/RenderRails$RenderState;FLorg/mtr/mapping/holder/Identifier;FFFF)V",at=@At("HEAD"))
    private static void point$begin(ClientWorld world,Rail rail,float y,@Coerce Object state,float width,Identifier texture,float u1,float v1,float u2,float v2,CallbackInfo ci){point$current.set(rail);}
    @Inject(method="renderRailStandard(Lorg/mtr/mapping/holder/ClientWorld;Lorg/mtr/core/data/Rail;FLorg/mtr/mod/render/RenderRails$RenderState;FLorg/mtr/mapping/holder/Identifier;FFFF)V",at=@At("RETURN"))
    private static void point$end(CallbackInfo ci){point$current.remove();}
    @Inject(method="lambda$renderRailStandard$16",at=@At("HEAD"),cancellable=true)
    private static void point$model(ClientWorld world,RailResource resource,boolean flip,boolean[] types,BlockPos pos,double x1,double z1,double x2,double z2,double x3,double z3,double x4,double z4,double y1,double y2,CallbackInfo ci){
        if(PointClient.suppress(point$current.get(),resource.getId(),new V3((x1+x3)/2,(y1+y2)/2,(z1+z3)/2),0)){PointRenderer.preserve(resource,flip,new V3(x1,y1,z1),new V3(x3,y2,z3));types[1]=true;ci.cancel();}
    }
    @Inject(method="lambda$renderRailStandard$19",at=@At("HEAD"),cancellable=true)
    private static void point$flat(@Coerce Object state,ClientWorld world,Identifier texture,float y,float u1,float v1,float u2,float v2,int color,BlockPos pos,double x1,double z1,double x2,double z2,double x3,double z3,double x4,double z4,double y1,double y2,CallbackInfo ci){
        // The vanilla rail texture is appearance. Preview, signal colour and one-way overlays remain native.
        if(texture.data.toString().equals("minecraft:textures/block/rail.png")&&PointClient.suppress(point$current.get(),"default",new V3((x1+x2+x3+x4)/4,(y1+y2)/2,(z1+z2+z3+z4)/4),0))ci.cancel();
    }
    @Inject(method="render",at=@At("RETURN")) private static void point$draw(CallbackInfo ci){point$current.remove();PointRenderer.render();}
}
