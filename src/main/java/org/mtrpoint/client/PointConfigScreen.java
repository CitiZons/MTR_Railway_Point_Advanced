package org.mtrpoint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrpoint.PointConfig;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/** Forge Mods-list configuration screen for local presentation settings. */
public final class PointConfigScreen extends Screen {
    private static final double DISTANCE_MAX=256;
    private final Screen parent;
    private double scale,near,far,animation,takeover;
    private Button done;
    public PointConfigScreen(Screen parent){super(Component.translatable("mtrpoint.config.title"));this.parent=parent;scale=PointConfig.uiScale();near=PointConfig.lodNear();far=PointConfig.lodFar();animation=PointConfig.animationDistance();takeover=PointConfig.takeoverDistance();}
    @Override protected void init(){
        addRenderableWidget(new AbstractSliderButton(width/2-130,height/2-116,260,20,Component.empty(),(scale-.5)/.75){
            {updateMessage();}
            @Override protected void updateMessage(){scale=.5+value*.75;scale=Math.round(scale*20)/20D;setMessage(Component.translatable("mtrpoint.config.ui_scale",Math.round(scale*100)));}
            @Override protected void applyValue(){scale=.5+value*.75;scale=Math.round(scale*20)/20D;updateMessage();}
        });
        distanceSlider("mtrpoint.config.lod_near",height/2-82,near,v->near=v);
        distanceSlider("mtrpoint.config.lod_far",height/2-52,far,v->far=v);
        distanceSlider("mtrpoint.config.animation_distance",height/2-22,animation,v->animation=v);
        distanceSlider("mtrpoint.config.takeover_distance",height/2+8,takeover,v->takeover=v);
        done=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.config.done"),b->save()).bounds(width/2-100,height/2+68,200,20).build());
        validate();
    }
    private void distanceSlider(String key,int y,double initial,DoubleConsumer setter){
        double shown=Math.max(0,Math.min(DISTANCE_MAX,initial));
        addRenderableWidget(new AbstractSliderButton(width/2-130,y,260,20,Component.empty(),shown/DISTANCE_MAX){
            private double metres=shown;
            {updateMessage();}
            @Override protected void updateMessage(){metres=Math.round(value*DISTANCE_MAX);value=metres/DISTANCE_MAX;setter.accept(metres);setMessage(Component.translatable(key).append(Component.literal(String.format(Locale.ROOT,"  %.0f m",metres))));validate();}
            @Override protected void applyValue(){updateMessage();}
        });
    }
    private boolean valid(){return near<=far;}
    private void validate(){if(done!=null)done.active=valid();}
    private void save(){if(!valid())return;PointConfig.UI_SCALE.set(scale);PointConfig.LOD_NEAR.set(near);PointConfig.LOD_FAR.set(far);PointConfig.ANIMATION_DISTANCE.set(animation);PointConfig.TAKEOVER_DISTANCE.set(takeover);PointConfig.SPEC.save();minecraft.setScreen(parent);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,height/2-144,0xffffffff);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
}
