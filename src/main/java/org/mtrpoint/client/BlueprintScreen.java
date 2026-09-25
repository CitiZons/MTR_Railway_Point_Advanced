package org.mtrpoint.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.mtrpoint.*;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Isolated single-turnout blueprint. All per-turnout controls and persistence rules come from
 * {@link TurnoutPanel}; this screen only supplies the dedicated viewport and the draft lifecycle. */
public final class BlueprintScreen extends Screen implements TurnoutPanel.Host {
    private final PointClient.View view;
    private PointSettings draft;private final Deque<PointSettings> undo=new ArrayDeque<>();
    private int selectedSleeper=-1;private boolean top=true,pending,previewDirty;private long previewAt;
    private double zoom=1,panX,panY,uiScale=1;private int viewportRight,viewportBottom,uiWidth,uiHeight;private long sentAt;
    private final TurnoutPanel panel=new TurnoutPanel(this);
    private final BlueprintWire wire=new BlueprintWire();
    private V3 projectionCenter,projectionForward,projectionNormal;private double projectionScale;private String status="";
    public BlueprintScreen(PointClient.View view){super(Component.translatable("mtrpoint.title"));this.view=view;draft=view.settings;}
    public String pointId(){return view.junction.id();}
    public TurnoutPanel panel(){return panel;}
    @Override public PointClient.View junction(){return view;}
    @Override public PointSettings draft(String id){return draft;}
    @Override public void changeDraft(String id,PointSettings next){
        if(next.equals(draft))return;
        if(undo.size()>=100)undo.removeLast();undo.push(draft);
        draft=next;previewDirty=true;previewAt=System.currentTimeMillis()+120;
    }
    /** Applies the current draft to the host preview right away and cancels the pending debounce.
     * Discrete panel actions call this so the visible model tracks the draft immediately; the
     * 120ms debounce stays reserved for continuous EditBox typing. */
    @Override public void previewNow(){previewDirty=false;view.preview(draft);}
    @Override public void status(String key){status=key;}
    @Override public void addWidget(AbstractWidget widget){addRenderableWidget(widget);}
    @Override public void refreshWidgets(){rebuildWidgets();}
    @Override public Font fontRenderer(){return font;}
    @Override public void apply(){
        flushPreview();pending=true;sentAt=System.currentTimeMillis();status="mtrpoint.saving";
        V3 c=view.junction.center();
        PointNetwork.send(new PointNetwork.Edit(view.junction.id(),BlockPos.containing(c.x(),c.y(),c.z()),PointClient.revision(view.junction.id()),AppearanceData.JSON.toJson(draft)));
    }
    @Override protected void init(){
        uiScale=PointUiScale.effective(width,height);uiWidth=(int)Math.floor(width/uiScale);uiHeight=(int)Math.floor(height/uiScale);
        viewportRight=Math.max(150,uiWidth-232);viewportBottom=uiHeight-52;
        // Reset/apply sit just above the screen's own undo/select_back/close strip instead of on it.
        // Any overlap makes the click land on whichever widget was added first, so a click on
        // "exit blueprint" would reach the panel's apply and save the draft it must discard.
        panel.init(viewportRight+12,34,80,viewportBottom,viewportBottom-30,uiHeight,2);
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(top?"mtrpoint.isometric":"mtrpoint.top"),b->{top=!top;rebuildWidgets();}).bounds(16,38,92,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.test_motion"),b->{double p=Double.isFinite(view.previewPosition)?view.previewPosition:view.position;view.previewPosition=view.junction.kind()==Junction.Kind.THREE?(p<.25?.5:p<.75?1:0):(p<.5?1:0);}).bounds(114,38,94,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.live"),b->{view.previewPosition=Double.NaN;}).bounds(214,38,62,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.undo"),b->{if(!undo.isEmpty()){draft=undo.pop();view.preview(draft);status="";rebuildWidgets();}}).bounds(16,uiHeight-36,72,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.select_back"),b->minecraft.setScreen(new PointSelectionScreen())).bounds(uiWidth-220,uiHeight-36,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.close"),b->onClose()).bounds(uiWidth-112,uiHeight-36,100,20).build());
        panel.sync();applyActive();
    }
    private void flushPreview(){if(previewDirty)previewNow();}
    public void acknowledge(String id,String message){if(!id.equals(view.junction.id()))return;pending=false;status=message;draft=PointClient.saved(id);view.preview(draft);rebuildWidgets();}
    private void applyActive(){panel.applyActive(!pending);}
    @Override public void tick(){if(previewDirty&&System.currentTimeMillis()>=previewAt)flushPreview();if(pending&&System.currentTimeMillis()-sentAt>5000){pending=false;status="mtrpoint.timeout";}applyActive();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void removed(){panel.addingSleeper=false;view.previewPosition=Double.NaN;view.preview(PointClient.saved(view.junction.id()));super.removed();}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        int mx=(int)(mouseX/uiScale),my=(int)(mouseY/uiScale);g.pose().pushPose();g.pose().scale((float)uiScale,(float)uiScale,1);
        g.fill(0,0,uiWidth,uiHeight,0xff111d2b);g.fill(8,65,viewportRight-6,viewportBottom,0xff163044);
        g.enableScissor(8,65,viewportRight-6,viewportBottom);
        for(int x=12;x<viewportRight;x+=16)g.vLine(x,65,viewportBottom,0xff244455);
        for(int y=65;y<viewportBottom;y+=16)g.hLine(8,viewportRight-6,y,0xff244455);
        prepareProjection();wire.draw(g,view.mesh(),view.renderedPosition(),this::project,selectedSleeper,viewportRight,viewportBottom);
        g.disableScissor();
        g.drawString(font,title,16,14,0xffe4f5f5,false);
        g.drawString(font,Component.translatable(view.junction.kind()==Junction.Kind.THREE?"mtrpoint.three":view.junction.kind()==Junction.Kind.Y?"mtrpoint.y":"mtrpoint.diamond"),viewportRight+12,16,0xfff1c56c,false);
        panel.render(g,mx,my);
        String state=view.styles.isEmpty()?"mtrpoint.need_profile":view.scissors!=null?"mtrpoint.scissors":view.state;
        g.drawString(font,Component.translatable(state),16,viewportBottom-30,0xfff1c56c,false);
        g.drawString(font,Component.translatable(selectedSleeper<0?"mtrpoint.help":"mtrpoint.drag_sleeper"),16,viewportBottom-16,0xffbdd4dd,false);
        if(!status.isBlank())g.drawString(font,Component.translatable(status),16,uiHeight-48,0xffe8ca82,false);
        super.render(g,mx,my,partialTick);
        g.pose().popPose();
    }
    private double scale(){double e=PointMesh.extent(view.junction,draft),w=view.junction.kind()!=Junction.Kind.DIAMOND?view.junction.a().at(e).distance(view.junction.b().at(e))+view.profile.gauge()+2:2*e+3,h=view.junction.kind()!=Junction.Kind.DIAMOND?e+3:2*e+3;if(!top){double d=w+h;w=d*.707;h=d*.35+2;}return Math.max(1,Math.min((viewportRight-35)/w,(viewportBottom-120)/h))*zoom;}
    private void prepareProjection(){projectionCenter=view.junction.kind()!=Junction.Kind.DIAMOND?view.junction.a().at(PointMesh.extent(view.junction,draft)/2):view.junction.center();projectionForward=view.junction.a().tangent(view.junction.sa());projectionNormal=projectionForward.lateral();projectionScale=scale();}
    private double[] project(V3 point){V3 d=point.sub(projectionCenter);double x=d.dot(projectionNormal),z=d.dot(projectionForward);return new double[]{viewportRight/2D+panX+(top?x:(x-z)*.707)*projectionScale,(65+viewportBottom)/2D+panY+(top?-z:(x+z)*.35-d.y()*1.8)*projectionScale};}
    @Override public boolean mouseScrolled(double x,double y,double delta){x/=uiScale;y/=uiScale;if(x<viewportRight&&y>65&&y<viewportBottom){zoom=Math.max(.3,Math.min(6,zoom*Math.pow(1.15,delta)));return true;}return super.mouseScrolled(x,y,delta);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){if(panel.keyPressed(key))return true;return super.keyPressed(key,scan,modifiers);}
    @Override public boolean mouseClicked(double x,double y,int button){
        x/=uiScale;y/=uiScale;
        if(panel.mouseClicked(x,y,button)){applyActive();return true;}
        if(x<viewportRight&&y>65&&y<viewportBottom&&button==0){prepareProjection();
            if(panel.addingSleeper){Track road=SleeperEdits.path(TurnoutPanel.sleeperTracks(view),draft.sleeperPath());double nearest=0,best=Double.MAX_VALUE;for(double d=0;d<=road.length;d+=.1){double[] p=project(road.at(d));double distance=Math.hypot(p[0]-x,p[1]-y);if(distance<best){best=distance;nearest=d;}}changeDraft(view.junction.id(),draft.addSleeper(draft.sleeperPath(),nearest));panel.addingSleeper=false;flushPreview();panel.selectedSleeper=draft.addedSleepers().keySet().stream().max(Integer::compareTo).orElse(-1);selectedSleeper=panel.selectedSleeper;status="mtrpoint.preview_changed";rebuildWidgets();return true;}
            var hit=SleeperHandles.hit(SleeperHandles.collect(view.mesh()),this::project,x,y,12);panel.selectedSleeper=hit==null?-1:hit.index();selectedSleeper=panel.selectedSleeper;
            panel.actionMenu=selectedSleeper>=0&&panel.page==2;if(panel.actionMenu){panel.actionX=16;panel.actionY=Math.max(67,Math.min(viewportBottom-114,(int)y-10));}return true;}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){x/=uiScale;y/=uiScale;dx/=uiScale;dy/=uiScale;panel.mouseDragged();if(x<viewportRight&&y>65&&y<viewportBottom){if(button==1){panX+=dx;panY+=dy;return true;}if(button==0&&selectedSleeper>=0){var handle=SleeperHandles.collect(view.mesh()).stream().filter(v->v.index()==selectedSleeper).findFirst().orElse(null);if(handle==null)return true;Track road=TurnoutPanel.sleeperTracks(view).stream().min(Comparator.comparingDouble(v->v.at(v.nearest(handle.center())).distance(handle.center()))).orElse(view.junction.a());V3 tangent=road.tangent(road.nearest(handle.center()));double[] a=project(handle.center()),b=project(handle.center().add(tangent));double sx=b[0]-a[0],sy=b[1]-a[1],length=sx*sx+sy*sy;double shift=length<1e-9?0:(dx*sx+dy*sy)/length;double old=draft.sleeperShifts().getOrDefault(selectedSleeper,0D);changeDraft(view.junction.id(),draft.sleeper(selectedSleeper,Math.max(-.25,Math.min(.25,old+shift))));return true;}}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(x/uiScale,y/uiScale,button);}
}
