package org.mtrpoint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.mtrpoint.PointConfig;

/** Forge Mods-list configuration screen for local presentation settings. */
public final class PointConfigScreen extends Screen {
    private final Screen parent;private double scale;
    private EditBox nearBox,farBox;private Button done;
    public PointConfigScreen(Screen parent){super(Component.translatable("mtrpoint.config.title"));this.parent=parent;scale=PointConfig.uiScale();}
    @Override protected void init(){
        String near=nearBox==null?Double.toString(PointConfig.lodNear()):nearBox.getValue();
        String far=farBox==null?Double.toString(PointConfig.lodFar()):farBox.getValue();
        done=null;
        addRenderableWidget(new AbstractSliderButton(width/2-130,height/2-70,260,20,Component.empty(),(scale-.5)/.75){
            @Override protected void updateMessage(){scale=.5+value*.75;scale=Math.round(scale*20)/20D;setMessage(Component.translatable("mtrpoint.config.ui_scale",Math.round(scale*100)));}
            @Override protected void applyValue(){scale=.5+value*.75;scale=Math.round(scale*20)/20D;updateMessage();}
        });
        nearBox=distanceBox("mtrpoint.config.lod_near",height/2-36,near);
        farBox=distanceBox("mtrpoint.config.lod_far",height/2-6,far);
        done=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.config.done"),b->save()).bounds(width/2-100,height/2+68,200,20).build());
        validate();
    }
    private EditBox distanceBox(String key,int y,String value){
        EditBox box=new EditBox(font,width/2+60,y,70,20,Component.translatable(key));
        box.setMaxLength(16);box.setValue(value);box.setTooltip(Tooltip.create(Component.translatable("mtrpoint.config.lod_help")));
        box.setResponder(v->validate());return addRenderableWidget(box);
    }
    private static double distance(EditBox box){
        try{double value=Double.parseDouble(box.getValue());return Double.isFinite(value)&&value>=0&&value<=4096?value:Double.NaN;}
        catch(NumberFormatException ex){return Double.NaN;}
    }
    private boolean valid(){double near=distance(nearBox),far=distance(farBox);return Double.isFinite(near)&&Double.isFinite(far)&&near<=far;}
    private void validate(){if(done!=null)done.active=valid();}
    private void save(){if(!valid())return;PointConfig.UI_SCALE.set(scale);PointConfig.LOD_NEAR.set(distance(nearBox));PointConfig.LOD_FAR.set(distance(farBox));PointConfig.SPEC.save();minecraft.setScreen(parent);}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick){
        renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,height/2-96,0xffffffff);
        graphics.drawString(font,Component.translatable("mtrpoint.config.lod_near"),width/2-130,height/2-30,0xffffffff);
        graphics.drawString(font,Component.translatable("mtrpoint.config.lod_far"),width/2-130,height/2,0xffffffff);
        graphics.drawCenteredString(font,Component.translatable(valid()?"mtrpoint.config.lod_equal":"mtrpoint.config.lod_invalid"),width/2,height/2+25,valid()?0xffaaaaaa:0xffff7777);
        graphics.drawCenteredString(font,Component.translatable("mtrpoint.config.lod_zero"),width/2,height/2+39,0xffaaaaaa);
        super.render(graphics,mouseX,mouseY,partialTick);
    }
}
