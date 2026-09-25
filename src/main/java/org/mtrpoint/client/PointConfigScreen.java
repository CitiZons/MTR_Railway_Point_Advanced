package org.mtrpoint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrpoint.PointConfig;

/** Forge Mods-list configuration screen for the local editor scale. */
public final class PointConfigScreen extends Screen {
    private final Screen parent;private double scale;
    public PointConfigScreen(Screen parent){super(Component.translatable("mtrpoint.config.title"));this.parent=parent;scale=PointConfig.uiScale();}
    @Override protected void init(){
        addRenderableWidget(new AbstractSliderButton(width/2-100,height/2-24,200,20,Component.empty(),(scale-.5)/.75){
            @Override protected void updateMessage(){scale=.5+value*.75;scale=Math.round(scale*20)/20D;setMessage(Component.translatable("mtrpoint.config.ui_scale",Math.round(scale*100)));}
            @Override protected void applyValue(){scale=.5+value*.75;scale=Math.round(scale*20)/20D;updateMessage();}
        });
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.config.done"),b->save()).bounds(width/2-100,height/2+12,200,20).build());
    }
    private void save(){PointConfig.UI_SCALE.set(scale);PointConfig.SPEC.save();minecraft.setScreen(parent);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,height/2-54,0xffffffff);super.render(graphics,mouseX,mouseY,partialTick);}
}
