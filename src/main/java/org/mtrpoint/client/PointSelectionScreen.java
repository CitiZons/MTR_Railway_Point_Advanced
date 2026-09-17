package org.mtrpoint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.mtrpoint.geometry.*;
import java.util.*;

/** A lightweight track plan with explicit, stable choices; no model builds to select a point. */
public final class PointSelectionScreen extends Screen {
    private final List<PointClient.View> points;
    private final Mesh plan=new Mesh();private final BlueprintWire wire=new BlueprintWire();
    private Mesh highlight;private int highlighted=-1;private final BlueprintWire highlightWire=new BlueprintWire();
    private V3 center;private double zoom=5,panX,panY;private int selected,page,right,bottom;private boolean framed;
    public PointSelectionScreen(){
        super(Component.translatable("mtrpoint.select_title"));var mc=net.minecraft.client.Minecraft.getInstance();
        center=new V3(mc.player.getX(),mc.player.getY(),mc.player.getZ());
        points=PointClient.views.stream().filter(v->v.junction.center().distance(center)<64).sorted(Comparator.comparing(v->v.junction.id())).toList();
        var roads=new LinkedHashMap<String,Track>();for(var v:points)for(Track t:v.junction.tracks())roads.putIfAbsent(t.id,t);
        for(Track t:roads.values())for(int i=1;i<t.points.size();i++){V3 a=t.points.get(i-1),b=t.points.get(i);plan.quad(a,b,b,a,Profile.STEEL,"rail",-1);}
        // Highlight the point nearest the gaze ray, not merely the player's feet.
        var look=mc.player.getLookAngle();V3 eye=new V3(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ()),dir=new V3(look.x,look.y,look.z);double best=Double.MAX_VALUE;
        for(int i=0;i<points.size();i++){V3 delta=PointClient.editCenter(points.get(i)).sub(eye);double along=Math.max(0,delta.dot(dir)),score=delta.sub(dir.mul(along)).length();if(score<best){selected=i;best=score;}}
        page=selected/7;
    }
    @Override protected void init(){
        right=width-192;bottom=height-45;
        if(!framed&&!points.isEmpty()){
            double x0=Double.MAX_VALUE,x1=-Double.MAX_VALUE,z0=x0,z1=x1;
            for(var v:points)for(V3 p:List.of(v.junction.center(),PointClient.editCenter(v))){x0=Math.min(x0,p.x());x1=Math.max(x1,p.x());z0=Math.min(z0,p.z());z1=Math.max(z1,p.z());}
            center=new V3((x0+x1)/2,center.y(),(z0+z1)/2);
            zoom=Math.max(.5,Math.min(5,Math.min((right-40)/(x1-x0+16),(bottom-95)/(z1-z0+16))));framed=true;
        }
        reveal();
        for(int row=0;row<7;row++){int id=page*7+row;if(id>=points.size())break;final int index=id;
            addRenderableWidget(BlueprintButton.blueprint(label(id),b->{selected=index;reveal();}).bounds(right+8,66+row*29,176,25).build());
        }
        addRenderableWidget(BlueprintButton.blueprint(Component.literal("◀"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(right+8,34,35,22).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.literal("▶"),b->{page=Math.min(Math.max(0,(points.size()-1)/7),page+1);rebuildWidgets();}).bounds(right+149,34,35,22).build());
        var open=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.select_open"),b->open()).bounds(width-184,height-30,176,22).build());open.active=!points.isEmpty();
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.select_close"),b->onClose()).bounds(12,height-30,90,22).build());
    }
    private Component label(int i){var v=points.get(i);return Component.literal((i+1)+" · ").append(Component.translatable(kind(v)));}
    private static String kind(PointClient.View v){return v.junction.kind()==Junction.Kind.Y?"mtrpoint.y":v.junction.kind()==Junction.Kind.THREE?"mtrpoint.three":"mtrpoint.diamond";}
    private double[] project(V3 p){return new double[]{right/2D+(p.x()-center.x())*zoom+panX,(65+bottom)/2D+(p.z()-center.z())*zoom+panY};}
    private void reveal(){if(points.isEmpty())return;double[] p=project(PointClient.editCenter(points.get(selected)));panX+=Math.max(25,Math.min(right-25,p[0]))-p[0];panY+=Math.max(85,Math.min(bottom-20,p[1]))-p[1];}
    private void open(){if(!points.isEmpty())minecraft.setScreen(new BlueprintScreen(points.get(selected)));}
    private void highlight(){
        if(points.isEmpty()||highlighted==selected)return;highlighted=selected;highlight=new Mesh();
        var v=points.get(selected);var j=v.junction;double extent=PointMesh.extent(j,v.settings);
        for(Track t:j.tracks()){
            double at=t==j.a()?j.sa():j.sb(),start=j.kind()==Junction.Kind.DIAMOND?Math.max(0,at-extent):0,end=Math.min(t.length,j.kind()==Junction.Kind.DIAMOND?at+extent:extent);
            for(double d=start;d<end;d+=.5){V3 a=t.at(d),b=t.at(Math.min(end,d+.5));highlight.quad(a,b,b,a,Profile.STEEL,"blade",-1);}
        }
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int x,int y,float delta){
        g.fill(0,0,width,height,0xff111d2b);g.fill(8,65,right-6,bottom,0xff163044);g.drawString(font,title,12,12,0xffe4f5f5,false);
        g.drawString(font,Component.translatable("mtrpoint.select_help"),12,38,0xffbdd4dd,false);
        g.enableScissor(8,65,right-6,bottom);wire.draw(g,plan,0,this::project,-1,right,bottom);
        highlight();if(highlight!=null)highlightWire.draw(g,highlight,0,this::project,-1,right,bottom);
        for(int i=0;i<points.size();i++){double[] p=project(PointClient.editCenter(points.get(i)));int px=(int)p[0],py=(int)p[1],color=i==selected?0xffffc467:0xff89d8e5;
            g.fill(px-7,py-7,px+8,py+8,0xff111d2b);g.drawCenteredString(font,Integer.toString(i+1),px,py-4,color);
            if(i==selected){g.hLine(px-9,px+9,py-9,color);g.hLine(px-9,px+9,py+9,color);g.vLine(px-9,py-9,py+9,color);g.vLine(px+9,py-9,py+9,color);}
        }
        g.disableScissor();g.drawCenteredString(font,(page+1)+" / "+Math.max(1,(points.size()+6)/7),right+96,42,0xffe4f5f5);
        if(points.isEmpty())g.drawString(font,Component.translatable("mtrpoint.no_point"),12,80,0xffffc467,false);
        else {var v=points.get(selected);var p=PointClient.editCenter(v);g.drawString(font,label(selected),right+8,bottom-49,0xffffc467,false);g.drawString(font,String.format(Locale.ROOT,"X %.1f  Z %.1f",p.x(),p.z()),right+8,bottom-35,0xffbdd4dd,false);}
        super.render(g,x,y,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0&&x<right&&y>=65&&y<bottom){int hit=-1;double best=14;for(int i=0;i<points.size();i++){double[] p=project(PointClient.editCenter(points.get(i)));double d=Math.hypot(x-p[0],y-p[1]);if(d<best){hit=i;best=d;}}if(hit>=0){selected=hit;page=selected/7;rebuildWidgets();}return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(button==1&&x<right){panX+=dx;panY+=dy;return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(x<right){zoom=Math.max(.5,Math.min(40,zoom*Math.pow(1.2,delta)));return true;}page=Math.max(0,Math.min(Math.max(0,(points.size()-1)/7),page+(delta>0?-1:1)));rebuildWidgets();return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ENTER){open();return true;}if(key==GLFW.GLFW_KEY_TAB&&!points.isEmpty()){selected=(selected+(hasShiftDown()?points.size()-1:1))%points.size();page=selected/7;rebuildWidgets();return true;}return super.keyPressed(key,scan,mods);}
}
