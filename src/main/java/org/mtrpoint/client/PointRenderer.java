package org.mtrpoint.client;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.render.*;
import org.mtrpoint.geometry.*;
import java.util.*;

public final class PointRenderer {
    private static final List<Mesh.Quad> PRESERVED=new ArrayList<>();
    public static void preserve(org.mtr.mod.resource.RailResource resource,boolean flip,V3 a,V3 b){
        V3 f=b.sub(a).unit(),n=f.lateral().mul(flip?1:-1),center=a.lerp(b,.5).add(0,resource.getModelYOffset(),0);double sign=flip?-1:1;
        java.util.function.Function<V3,V3> transform=v->center.add(n.mul(v.x())).add(f.mul(v.z()*sign)).add(0,v.y(),0);
        for(var q:Profiles.attachments(resource.getId()))PRESERVED.add(new Mesh.Quad(transform.apply(q.a()),transform.apply(q.b()),transform.apply(q.c()),transform.apply(q.d()),q.surface(),q.part(),-1));
    }
    public static void render(){
        var mc=net.minecraft.client.Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        Map<Profile.Surface,List<Mesh.Quad>> batches=new HashMap<>();
        for(var q:PRESERVED)batches.computeIfAbsent(q.surface(),k->new ArrayList<>()).add(q);PRESERVED.clear();
        for(var view:PointClient.views){
            if(view.styles.isEmpty()||!view.settings.enabled()||view.junction.center().distance(new V3(mc.player.getX(),mc.player.getY(),mc.player.getZ()))>128)continue;
            for(var quad:view.mesh().quads)batches.computeIfAbsent(quad.surface(),k->new ArrayList<>()).add(quad);
        }
        Map<Long,Integer> lights=new HashMap<>();
        batches.forEach((surface,quads)->MainRenderer.scheduleRender(new Identifier(surface.texture()),false,QueuedRenderLayer.EXTERIOR,(graphics,offset)->{
            for(var q:quads){V3 a=q.a(),b=q.b(),c=q.c(),d=q.d();V3 mid=q.center();
                var block=net.minecraft.core.BlockPos.containing(mid.x(),mid.y()+.3,mid.z());int light=lights.computeIfAbsent(block.asLong(),k->net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level,block));
                Direction normal=normal(a,b,c);
                IDrawing.drawTexture(graphics,a.x(),a.y(),a.z(),b.x(),b.y(),b.z(),c.x(),c.y(),c.z(),d.x(),d.y(),d.z(),offset,surface.u0(),surface.v0(),surface.u1(),surface.v1(),normal,surface.color(),light);
            }
        }));
    }
    private static Direction normal(V3 a,V3 b,V3 c){
        V3 u=b.sub(a),v=c.sub(a);double x=u.y()*v.z()-u.z()*v.y(),y=u.z()*v.x()-u.x()*v.z(),z=u.x()*v.y()-u.y()*v.x();
        if(Math.abs(y)>=Math.abs(x)&&Math.abs(y)>=Math.abs(z))return y>=0?Direction.UP:Direction.DOWN;
        if(Math.abs(x)>=Math.abs(z))return x>=0?Direction.EAST:Direction.WEST;
        return z>=0?Direction.SOUTH:Direction.NORTH;
    }
}
