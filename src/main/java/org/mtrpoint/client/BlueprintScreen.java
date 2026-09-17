package org.mtrpoint.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mtrpoint.*;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Original blueprint UI: live mesh projection, numeric tuning, direct sleeper handles, undo and reset. */
public final class BlueprintScreen extends Screen {
    private static final String[] NAMES={"length","blade","throw","flangeway","spacing","sleeper_width","sleeper_height","overhang","gauge","rail_top","head_width","height","frog_shift","duration","sleeper_angle","sleeper_end_angle","sleeper_mode","guard_shift","guard_length","guard_gap","wing_shift","wing_length","wing_gap","nose_length"};
    private static final int[][] PAGES={{0,8,9,10,11,13},{1,2,3,12},{4,5,6,7,14,15,16},{17,18,19,20,21,22,23}};
    private static final double[] STEP={.05,.5,.01,.005,.05,.02,.02,.05,.01,.01,.005,.01,.05,.05,1,1,1,.05,.1,.005,.05,.1,.005,.1};
    private final PointClient.View view;
    private PointSettings draft;private final Deque<PointSettings> undo=new ArrayDeque<>();
    private int page,selectedSleeper=-1,rowStep;private boolean top=true,pending,previewDirty;private long previewAt;
    private double zoom=1,panX,panY;private int viewportRight,viewportBottom;private long sentAt;
    private EditBox[] fields=new EditBox[7];
    private boolean sleeperMenu;private int menuX,menuY;
    private final List<String> profileChoices;
    private final BlueprintWire wire=new BlueprintWire();
    private V3 projectionCenter,projectionForward,projectionNormal;private double projectionScale;private Button apply;private String status="";
    public BlueprintScreen(PointClient.View view){super(Component.translatable("mtrpoint.title"));this.view=view;draft=view.settings;profileChoices=PointClient.profileChoices(view.junction);}
    public String pointId(){return view.junction.id();}
    @Override protected void init(){
        sleeperMenu=false;
        viewportRight=Math.max(150,width-232);viewportBottom=height-52;int x=viewportRight+12;rowStep=Math.min(27,Math.max(19,(height-198)/7));int controls=80+rowStep*7;
        for(int tab=0;tab<4;tab++){final int target=tab;
            Button button=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.tab_"+tab),b->{page=target;rebuildWidgets();}).bounds(x+(tab%2)*106,34+(tab/2)*22,102,20).build());
            button.active=tab!=page;
        }
        Arrays.fill(fields,null);
        for(int i=0;i<PAGES[page].length;i++){
            int idx=PAGES[page][i],y=80+i*rowStep;final int index=idx;
            if(idx==16){addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.sleeper_mode_"+draft.sleeperMode()),b->{sleeperMenu=!sleeperMenu;menuX=x;menuY=Math.max(66,Math.min(height-140,y+16));}).bounds(x,y-4,208,20).tooltip(Tooltip.create(Component.translatable("mtrpoint.tip_sleeper_mode"))).build());continue;}
            EditBox field=new EditBox(font,x+91,y-4,75,20,Component.translatable("mtrpoint."+NAMES[idx]));field.setMaxLength(8);field.setValue(String.format(Locale.ROOT,"%.3f",draft.values()[idx]));
            field.setTooltip(Tooltip.create(Component.translatable("mtrpoint.tip_"+NAMES[idx])));
            field.setResponder(text->{try{PointSettings next=draft.with(index,Double.parseDouble(text));if(!next.equals(draft)){remember();draft=next;previewDirty=true;previewAt=System.currentTimeMillis()+180;}field.setTextColor(0xe9f5f6);}catch(RuntimeException ex){field.setTextColor(0xff7777);}});fields[i]=addRenderableWidget(field);
            addRenderableWidget(BlueprintButton.blueprint(Component.literal("−"),b->adjust(index,-STEP[index])).bounds(x+170,y-4,17,20).build());
            addRenderableWidget(BlueprintButton.blueprint(Component.literal("+"),b->adjust(index,STEP[index])).bounds(x+190,y-4,17,20).build());
        }
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(draft.movableFrog()?"mtrpoint.frog_movable":"mtrpoint.frog_fixed"),b->{change(draft.flags(!draft.movableFrog(),draft.enabled()));rebuildWidgets();}).bounds(x,controls,208,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(draft.enabled()?"mtrpoint.enabled":"mtrpoint.disabled"),b->{change(draft.flags(draft.movableFrog(),!draft.enabled()));rebuildWidgets();}).bounds(x,controls+24,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.profile"),b->cycleProfile()).bounds(x+108,controls+24,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(top?"mtrpoint.isometric":"mtrpoint.top"),b->{top=!top;rebuildWidgets();}).bounds(16,38,92,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.test_motion"),b->{double p=Double.isFinite(view.previewPosition)?view.previewPosition:view.position;view.previewPosition=view.junction.kind()==Junction.Kind.THREE?(p<.25?.5:p<.75?1:0):(p<.5?1:0);}).bounds(114,38,94,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.live"),b->{view.previewPosition=Double.NaN;}).bounds(214,38,62,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.undo"),b->{if(!undo.isEmpty()){draft=undo.pop();view.preview(draft);rebuildWidgets();}}).bounds(16,height-36,72,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.reset"),b->{change(PointSettings.DEFAULT);rebuildWidgets();}).bounds(94,height-36,94,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.select_back"),b->minecraft.setScreen(new PointSelectionScreen())).bounds(194,height-36,108,20).build());
        apply=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.apply"),b->save()).bounds(width-220,height-36,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.close"),b->onClose()).bounds(width-112,height-36,100,20).build());
    }
    private void adjust(int index,double delta){try{change(draft.with(index,Math.round((draft.values()[index]+delta)*1000)/1000D));rebuildWidgets();}catch(IllegalArgumentException ignored){}}
    private void remember(){if(undo.size()>=100)undo.removeLast();undo.push(draft);}
    private void change(PointSettings next){if(next.equals(draft))return;remember();draft=next;previewDirty=true;previewAt=System.currentTimeMillis()+120;}
    private void flushPreview(){if(previewDirty){previewDirty=false;view.preview(draft);}}
    private void cycleProfile(){int i=profileChoices.indexOf(draft.profileStyle());change(draft.style(profileChoices.get((i+1)%profileChoices.size())));status="mtrpoint.preview_changed";rebuildWidgets();}
    private void save(){
        try{double[] values=draft.values();for(int i=0;i<fields.length;i++)if(fields[i]!=null)values[PAGES[page][i]]=Double.parseDouble(fields[i].getValue());draft=PointSettings.of(values,draft.movableFrog(),draft.enabled(),draft.sleeperShifts(),draft.profileStyle());}catch(IllegalArgumentException ex){status="mtrpoint.invalid";return;}
        flushPreview();pending=true;sentAt=System.currentTimeMillis();status="mtrpoint.saving";V3 c=view.junction.center();
        PointNetwork.send(new PointNetwork.Edit(view.junction.id(),BlockPos.containing(c.x(),c.y(),c.z()),PointClient.revision(view.junction.id()),AppearanceData.JSON.toJson(draft)));
    }
    public void acknowledge(String id,String message){if(!id.equals(view.junction.id()))return;pending=false;status=message;draft=PointClient.saved(id);view.preview(draft);rebuildWidgets();}
    @Override public void tick(){if(previewDirty&&System.currentTimeMillis()>=previewAt)flushPreview();if(pending&&System.currentTimeMillis()-sentAt>5000){pending=false;status="mtrpoint.timeout";}if(apply!=null)apply.active=!pending;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){view.previewPosition=Double.NaN;view.preview(PointClient.saved(view.junction.id()));super.removed();}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        g.fill(0,0,width,height,0xff111d2b);g.fill(8,65,viewportRight-6,viewportBottom,0xff163044);
        g.enableScissor(8,65,viewportRight-6,viewportBottom);
        for(int x=12;x<viewportRight;x+=16)g.vLine(x,65,viewportBottom,0xff244455);
        for(int y=65;y<viewportBottom;y+=16)g.hLine(8,viewportRight-6,y,0xff244455);
        prepareProjection();wire.draw(g,view.mesh(),view.renderedPosition(),this::project,selectedSleeper,viewportRight,viewportBottom);
        g.disableScissor();
        g.drawString(font,title,16,14,0xffe4f5f5,false);
        g.drawString(font,Component.translatable(view.junction.kind()==Junction.Kind.THREE?"mtrpoint.three":view.junction.kind()==Junction.Kind.Y?"mtrpoint.y":"mtrpoint.diamond"),viewportRight+12,16,0xfff1c56c,false);
        for(int i=0;i<PAGES[page].length;i++)if(PAGES[page][i]!=16)g.drawString(font,font.plainSubstrByWidth(Component.translatable("mtrpoint."+NAMES[PAGES[page][i]]).getString(),88),viewportRight+12,80+i*rowStep,0xffbdd4dd,false);
        String profile=(draft.profileStyle().isBlank()?Component.translatable("mtrpoint.auto").getString()+" → ":"")+profileName(view.profile.source());g.drawString(font,font.plainSubstrByWidth(profile,205),viewportRight+12,80+rowStep*7+50,0xff89d8e5,false);
        String state=view.styles.isEmpty()?"mtrpoint.need_profile":view.scissors!=null?"mtrpoint.scissors":view.state;
        g.drawString(font,Component.translatable(state),16,viewportBottom-30,0xfff1c56c,false);
        g.drawString(font,Component.translatable(selectedSleeper<0?"mtrpoint.help":"mtrpoint.drag_sleeper"),16,viewportBottom-16,0xffbdd4dd,false);
        if(!status.isBlank())g.drawString(font,Component.translatable(status),16,height-48,0xffe8ca82,false);
        super.render(g,mouseX,mouseY,partialTick);
        if(sleeperMenu){
            g.pose().pushPose();g.pose().translate(0,0,400);g.fill(menuX-2,menuY-2,menuX+210,menuY+112,0xff527987);
            for(int mode=0;mode<5;mode++){int y=menuY+mode*22;boolean hover=mouseX>=menuX&&mouseX<menuX+208&&mouseY>=y&&mouseY<y+22;
                g.fill(menuX,y,menuX+208,y+22,hover?0xff31586a:0xff163044);
                g.drawString(font,Component.translatable("mtrpoint.sleeper_mode_"+mode),menuX+6,y+7,mode==draft.sleeperMode()?0xfff1c56c:0xffe4f5f5,false);
            }g.pose().popPose();return;
        }
        for(int i=0;i<PAGES[page].length;i++)if(mouseX>=viewportRight+12&&mouseX<viewportRight+102&&mouseY>=76+i*rowStep&&mouseY<96+i*rowStep)
            g.renderTooltip(font,font.split(Component.translatable("mtrpoint.tip_"+NAMES[PAGES[page][i]]),220),mouseX,mouseY);
    }
    private double scale(){double e=PointMesh.extent(view.junction,draft),w=view.junction.kind()!=Junction.Kind.DIAMOND?view.junction.a().at(e).distance(view.junction.b().at(e))+view.profile.gauge()+2:2*e+3,h=view.junction.kind()!=Junction.Kind.DIAMOND?e+3:2*e+3;if(!top){double d=w+h;w=d*.707;h=d*.35+2;}return Math.max(1,Math.min((viewportRight-35)/w,(viewportBottom-120)/h))*zoom;}
    private static String profileName(String id){return switch(id){case "default_3d"->Component.translatable("mtrpoint.native_regular").getString();case "default_3d_siding"->Component.translatable("mtrpoint.native_siding").getString();default->id;};}
    private void prepareProjection(){projectionCenter=view.junction.kind()!=Junction.Kind.DIAMOND?view.junction.a().at(PointMesh.extent(view.junction,draft)/2):view.junction.center();projectionForward=view.junction.a().tangent(view.junction.sa());projectionNormal=projectionForward.lateral();projectionScale=scale();}
    private double[] project(V3 point){V3 d=point.sub(projectionCenter);double x=d.dot(projectionNormal),z=d.dot(projectionForward);return new double[]{viewportRight/2D+panX+(top?x:(x-z)*.707)*projectionScale,(65+viewportBottom)/2D+panY+(top?-z:(x+z)*.35-d.y()*1.8)*projectionScale};}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(x<viewportRight&&y>65&&y<viewportBottom){zoom=Math.max(.3,Math.min(6,zoom*Math.pow(1.15,delta)));return true;}return super.mouseScrolled(x,y,delta);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(sleeperMenu){if(key==256){sleeperMenu=false;return true;}if(key==264||key==265){change(draft.with(16,(draft.sleeperMode()+(key==264?1:4))%5));return true;}if(key==257){sleeperMenu=false;rebuildWidgets();return true;}return true;}return super.keyPressed(key,scan,modifiers);}
    @Override public boolean mouseClicked(double x,double y,int button){if(sleeperMenu){if(button==0&&x>=menuX&&x<menuX+208&&y>=menuY&&y<menuY+110)change(draft.with(16,(int)((y-menuY)/22)));sleeperMenu=false;rebuildWidgets();return true;}if(x<viewportRight&&y>65&&y<viewportBottom&&button==0){prepareProjection();double best=12;selectedSleeper=-1;for(var q:view.mesh().quads)if(q.part().equals("sleeper")){double[] p=project(q.center());double distance=Math.hypot(p[0]-x,p[1]-y);if(distance<best){best=distance;selectedSleeper=q.index();}}return true;}return super.mouseClicked(x,y,button);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(x<viewportRight&&y>65&&y<viewportBottom){if(button==1){panX+=dx;panY+=dy;return true;}if(button==0&&selectedSleeper>=0){double old=draft.sleeperShifts().getOrDefault(selectedSleeper,0D);change(draft.sleeper(selectedSleeper,Math.max(-.25,Math.min(.25,old-dy/scale()))));return true;}}return super.mouseDragged(x,y,button,dx,dy);}
}
