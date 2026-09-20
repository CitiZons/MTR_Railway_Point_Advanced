package org.mtrpoint.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * Per-turnout appearance editor shared by BlueprintScreen (isolated viewport) and
 * PointSelectionScreen (unified complex-junction plan). Numeric pages, sleeper rules and the
 * menu handling live here once, so the two entry points cannot drift apart. Draft storage,
 * undo and server persistence stay with the hosting screen, which is also what refreshes the
 * preview geometry.
 */
public final class TurnoutPanel {
    public static final String[] NAMES={"length","blade","throw","flangeway","spacing","sleeper_width","sleeper_height","overhang","gauge","rail_top","head_width","height","frog_shift","duration","sleeper_angle","sleeper_end_angle","sleeper_mode","guard_shift","guard_length","guard_gap","wing_shift","wing_length","wing_gap","nose_length"};
    public static final int[][] PAGES={{0,8,9,10,11,13},{1,2,3,12},{4,5,6,7,14,15,16},{17,18,19,20,21,22,23}};
    public static final double[] STEP={.05,.5,.01,.005,.05,.02,.02,.05,.01,.01,.005,.01,.05,.05,1,1,1,.05,.1,.005,.05,.1,.005,.1};
    private static final int ROWS=7,RESERVED=68;

    /** The hosting screen owns the draft, its undo stack, the save packet and its own widgets. */
    public interface Host {
        PointClient.View junction();
        PointSettings draft(String id);
        void changeDraft(String id,PointSettings next);
        /** Host-specific refresh after a discrete action. {@link #change} calls this exactly once
         * immediately after {@link #changeDraft}; the EditBox responder never does, so the 120ms
         * debounce that protects continuous typing is untouched. */
        void previewNow();
        void apply();
        void status(String key);
        void addWidget(AbstractWidget widget);
        void refreshWidgets();
        Font fontRenderer();
    }

    private final Host host;
    public int page,rowStep=27,selectedSleeper=-1;
    public boolean modeMenu,actionMenu,addingSleeper;
    public int menuX,menuY,actionX,actionY;
    private int x,rowsTop,width=208,uiHeight,tabColumns=2;
    private boolean syncing;
    private final EditBox[] fields=new EditBox[ROWS];
    private final Button[] tabs=new Button[4];
    private Button sleeperMode,frog,enabled,profile,sleeperPath,reset,apply;
    private List<String> profileChoices=List.of("");

    public TurnoutPanel(Host host){this.host=host;}
    public PointClient.View view(){return host.junction();}
    private String id(){PointClient.View view=view();return view==null?null:view.junction.id();}
    private PointSettings draft(){String id=id();return id==null?PointSettings.DEFAULT:host.draft(id);}
    /** Sleeper coordinates follow the shared crossing of a scissors layout. */
    public static List<Track> sleeperTracks(PointClient.View view){return view.scissors!=null&&view.junction.kind()==Junction.Kind.DIAMOND?view.scissors.tracks():view.junction.tracks();}
    public static String profileName(String id){return switch(id){case "default_3d"->Component.translatable("mtrpoint.native_regular").getString();case "default_3d_siding"->Component.translatable("mtrpoint.native_siding").getString();default->id;};}
    public Button applyButton(){return apply;}

    private Button add(Button button){host.addWidget(button);return button;}
    private void rebuild(){host.refreshWidgets();}

    public void init(int x,int tabsTop,int rowsTop,int bottomLimit,int bottomRow,int uiHeight,int tabColumns){
        this.x=x;this.rowsTop=rowsTop;this.uiHeight=uiHeight;this.tabColumns=tabColumns;
        // A rebuild may close the transient popups, but the armed add-sleeper tool outlives its own
        // menu click: the menu handler rebuilds on the way out and the next viewport click places the
        // sleeper. That click clears the flag, and every host cancels it on teardown, so keeping it
        // across init() cannot leak the tool outside the screen that armed it.
        modeMenu=false;actionMenu=false;
        PointClient.View view=view();profileChoices=view==null?List.of(""):PointClient.profileChoices(view.junction);
        rowStep=Math.max(20,Math.min(27,(bottomLimit-rowsTop-RESERVED)/ROWS));
        for(int tab=0;tab<4;tab++){final int target=tab;
            Button button=add(BlueprintButton.blueprint(Component.translatable("mtrpoint.tab_"+tab),b->{page=target;rebuild();}).bounds(x+(tab%tabColumns)*(width/tabColumns),tabsTop+(tab/tabColumns)*22,width/tabColumns-4,20).build());
            button.active=tab!=page;tabs[tab]=button;
        }
        Arrays.fill(fields,null);PointSettings draft=draft();
        for(int i=0;i<PAGES[page].length;i++){
            int index=PAGES[page][i],y=rowsTop+i*rowStep;
            if(index==16){sleeperMode=add(BlueprintButton.blueprint(Component.translatable("mtrpoint.sleeper_mode_"+draft.sleeperMode()),b->{modeMenu=!modeMenu;actionMenu=false;menuX=x;menuY=Math.max(tabsTop+32,Math.min(uiHeight-140,y+16));}).bounds(x,y-4,width,20).tooltip(Tooltip.create(Component.translatable("mtrpoint.tip_sleeper_mode"))).build());continue;}
            EditBox field=new EditBox(host.fontRenderer(),x+91,y-4,75,20,Component.translatable("mtrpoint."+NAMES[index]));
            field.setMaxLength(8);field.setValue(String.format(Locale.ROOT,"%.3f",draft.values()[index]));
            field.setTooltip(Tooltip.create(Component.translatable("mtrpoint.tip_"+NAMES[index])));
            field.setResponder(text->edit(field,index,text));host.addWidget(field);fields[i]=field;
            add(BlueprintButton.blueprint(Component.literal("−"),b->adjust(index,-STEP[index])).bounds(x+170,y-4,17,20).build());
            add(BlueprintButton.blueprint(Component.literal("+"),b->adjust(index,STEP[index])).bounds(x+190,y-4,17,20).build());
        }
        int controls=rowsTop+rowStep*ROWS;
        frog=add(BlueprintButton.blueprint(Component.translatable(draft.movableFrog()?"mtrpoint.frog_movable":"mtrpoint.frog_fixed"),b->change(draft().flags(!draft().movableFrog(),draft().enabled()))).bounds(x,controls,width,20).build());
        enabled=add(BlueprintButton.blueprint(Component.translatable(draft.enabled()?"mtrpoint.enabled":"mtrpoint.disabled"),b->change(draft().flags(draft().movableFrog(),!draft().enabled()))).bounds(x,controls+24,100,20).build());
        profile=add(BlueprintButton.blueprint(Component.translatable("mtrpoint.profile"),b->cycleProfile()).bounds(x+108,controls+24,100,20).build());
        sleeperPath=page==2?add(BlueprintButton.blueprint(Component.translatable("mtrpoint.sleeper_path",draft.sleeperPath()+1),b->change(draft().sleeperPath((draft().sleeperPath()+1)%sleeperTracks(view()).size()))).bounds(x,controls+48,width,20).build()):null;
        reset=add(BlueprintButton.blueprint(Component.translatable("mtrpoint.reset"),b->change(PointSettings.DEFAULT)).bounds(x,bottomRow,100,20).build());
        apply=add(BlueprintButton.blueprint(Component.translatable("mtrpoint.apply"),b->save()).bounds(x+108,bottomRow,100,20).build());
    }

    /** Push draft state back into the controls after an undo, a remote refresh or a reset. */
    public void sync(){
        if(fields[0]==null&&sleeperMode==null&&frog==null)return;
        PointSettings draft=draft();syncing=true;
        try{
            for(int i=0;i<fields.length;i++){
                EditBox field=fields[i];if(field==null||field.isFocused())continue;
                String text=String.format(Locale.ROOT,"%.3f",draft.values()[PAGES[page][i]]);
                if(!text.equals(field.getValue()))field.setValue(text);
            }
            if(sleeperMode!=null)sleeperMode.setMessage(Component.translatable("mtrpoint.sleeper_mode_"+draft.sleeperMode()));
            if(frog!=null)frog.setMessage(Component.translatable(draft.movableFrog()?"mtrpoint.frog_movable":"mtrpoint.frog_fixed"));
            if(enabled!=null)enabled.setMessage(Component.translatable(draft.enabled()?"mtrpoint.enabled":"mtrpoint.disabled"));
            if(sleeperPath!=null)sleeperPath.setMessage(Component.translatable("mtrpoint.sleeper_path",draft.sleeperPath()+1));
            for(int tab=0;tab<tabs.length;tab++)if(tabs[tab]!=null)tabs[tab].active=tab!=page;
        }finally{syncing=false;}
    }
    public void applyActive(boolean active){if(apply!=null)apply.active=active;}

    private void edit(EditBox field,int index,String text){
        if(syncing)return;
        try{PointSettings next=draft().with(index,Double.parseDouble(text));if(!next.equals(draft())){host.changeDraft(id(),next);host.status("mtrpoint.preview_changed");}field.setTextColor(0xe9f5f6);}
        catch(RuntimeException ex){field.setTextColor(0xff7777);}
    }
    /** Discrete action: every control outside the EditBoxes commits and shows the model in the
     * same frame instead of waiting for the typing debounce. Undo/history stay with the host. */
    private void change(PointSettings next){
        if(next.equals(draft()))return;
        host.changeDraft(id(),next);host.previewNow();host.status("mtrpoint.preview_changed");sync();
    }
    private void adjust(int index,double delta){try{change(draft().with(index,Math.round((draft().values()[index]+delta)*1000)/1000D));}catch(IllegalArgumentException ignored){}}
    private void cycleProfile(){int index=profileChoices.indexOf(draft().profileStyle());change(draft().style(profileChoices.get((index+1)%profileChoices.size())));}
    private void save(){
        PointSettings current=draft();
        try{
            double[] values=current.values();
            for(int i=0;i<fields.length;i++)if(fields[i]!=null)values[PAGES[page][i]]=Double.parseDouble(fields[i].getValue());
            current=PointSettings.of(values,current.movableFrog(),current.enabled(),current.sleeperShifts(),current.profileStyle(),current.sleeperPath(),current.sleeperOverrides(),current.addedSleepers());
        }catch(IllegalArgumentException ex){host.status("mtrpoint.invalid");return;}
        host.changeDraft(id(),current);host.apply();
    }
    private void runSleeperAction(int item){
        PointSettings draft=draft();
        if(item==0){addingSleeper=true;host.status("mtrpoint.sleeper_add_help");return;}
        if(item==1){selectedSleeper=-1;change(draft.resetSleepers());return;}
        if(selectedSleeper<0)return;
        if(item==2){change(draft.removeSleeper(selectedSleeper));selectedSleeper=-1;}
        else if(item==3)change(draft.toggleSleeper(selectedSleeper,PointSettings.SLEEPER_SPLIT));
        else change(draft.toggleSleeper(selectedSleeper,PointSettings.SLEEPER_FULL));
    }

    public boolean mouseClicked(double px,double py,int button){
        if(modeMenu){
            if(button==0&&px>=menuX&&px<menuX+width&&py>=menuY&&py<menuY+110)change(draft().with(16,(int)((py-menuY)/22)));
            modeMenu=false;rebuild();return true;
        }
        if(actionMenu){
            if(button==0&&px>=actionX&&px<actionX+128){
                int item=py>=actionY&&py<actionY+44?(int)((py-actionY)/22):py>=actionY+46&&py<actionY+112?2+(int)((py-actionY-46)/22):-1;
                if(item>=0&&item<5)runSleeperAction(item);
            }
            actionMenu=false;rebuild();return true;
        }
        return false;
    }
    public void mouseDragged(){actionMenu=false;}
    public boolean keyPressed(int key){
        if(modeMenu){
            if(key==256){modeMenu=false;return true;}
            if(key==264||key==265){change(draft().with(16,(draft().sleeperMode()+(key==264?1:4))%5));return true;}
            if(key==257){modeMenu=false;rebuild();return true;}
            return true;
        }
        if(actionMenu&&key==256){actionMenu=false;return true;}
        return false;
    }

    public void render(GuiGraphics g,int mouseX,int mouseY){
        if(view()==null)return;
        Font font=host.fontRenderer();PointSettings draft=draft();
        for(int i=0;i<PAGES[page].length;i++)if(PAGES[page][i]!=16)g.drawString(font,font.plainSubstrByWidth(Component.translatable("mtrpoint."+NAMES[PAGES[page][i]]).getString(),88),x,rowsTop+i*rowStep,0xffbdd4dd,false);
        String source=(draft.profileStyle().isBlank()?Component.translatable("mtrpoint.auto").getString()+" → ":"")+profileName(view().profile.source());
        int profileY=rowsTop+rowStep*ROWS+(page==2?74:50);
        if(profileY<uiHeight-42)g.drawString(font,font.plainSubstrByWidth(source,width-3),x,profileY,0xff89d8e5,false);
        if(modeMenu){
            g.pose().pushPose();g.pose().translate(0,0,400);g.fill(menuX-2,menuY-2,menuX+width+2,menuY+112,0xff527987);
            for(int mode=0;mode<5;mode++){int y=menuY+mode*22;boolean hover=mouseX>=menuX&&mouseX<menuX+width&&mouseY>=y&&mouseY<y+22;
                g.fill(menuX,y,menuX+width,y+22,hover?0xff31586a:0xff163044);
                g.drawString(font,Component.translatable("mtrpoint.sleeper_mode_"+mode),menuX+6,y+7,mode==draft.sleeperMode()?0xfff1c56c:0xffe4f5f5,false);
            }g.pose().popPose();
        }else if(actionMenu){
            String[] labels={"mtrpoint.sleeper_add","mtrpoint.sleeper_reset","mtrpoint.sleeper_delete",draft.sleeper(selectedSleeper,PointSettings.SLEEPER_SPLIT)?"mtrpoint.sleeper_join":"mtrpoint.sleeper_split",draft.sleeper(selectedSleeper,PointSettings.SLEEPER_FULL)?"mtrpoint.sleeper_auto_shape":"mtrpoint.sleeper_full"};
            g.pose().pushPose();g.pose().translate(0,0,410);g.fill(actionX-2,actionY-2,actionX+130,actionY+114,0xff527987);
            for(int item=0;item<labels.length;item++){int y=actionY+item*22+(item>=2?2:0);boolean hover=mouseX>=actionX&&mouseX<actionX+128&&mouseY>=y&&mouseY<y+22;
                g.fill(actionX,y,actionX+128,y+22,hover?0xff31586a:0xff163044);g.drawString(font,font.plainSubstrByWidth(Component.translatable(labels[item]).getString(),116),actionX+6,y+7,0xffe4f5f5,false);
            }g.pose().popPose();
        }
        if(!modeMenu&&!actionMenu)for(int i=0;i<PAGES[page].length;i++)if(PAGES[page][i]!=16&&mouseX>=x&&mouseX<x+90&&mouseY>=rowsTop+i*rowStep-4&&mouseY<rowsTop+i*rowStep+16)
            g.renderTooltip(font,font.split(Component.translatable("mtrpoint.tip_"+NAMES[PAGES[page][i]]),220),mouseX,mouseY);
    }
}
