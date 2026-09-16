package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public final class BlueprintButton extends Button {
    private BlueprintButton(Builder builder){super(builder);}
    public static Builder blueprint(Component text,OnPress action){return new Builder(text,action){@Override public Button build(){return new BlueprintButton(this);}};}
    @Override public void renderWidget(GuiGraphics g,int x,int y,float partialTick){
        int edge=active?(isHoveredOrFocused()?0xffffc467:0xff527889):0xff344551;
        g.fill(getX(),getY(),getX()+width,getY()+height,edge);
        g.fill(getX()+1,getY()+1,getX()+width-1,getY()+height-1,isHoveredOrFocused()?0xff29485a:0xff1c3345);
        g.fill(getX()+3,getY()+3,getX()+5,getY()+height-3,edge);
        g.drawCenteredString(Minecraft.getInstance().font,getMessage(),getX()+width/2,getY()+(height-8)/2,active?0xffe0eef1:0xff70838c);
    }
}
