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
    private static final String[] NAMES={"length","blade","throw","flangeway","spacing","sleeper_width","sleeper_height","overhang","gauge","rail_top","head_width","height","frog_shift","duration"};
    private static final double[] STEP={.05,.5,.01,.005,.05,.02,.02,.05,.01,.01,.005,.01,.05,.05};
    private final PointClient.View view;
    private PointSettings draft;private final Deque<PointSettings> undo=new ArrayDeque<>();
    private int page,selectedSleeper=-1,rowStep;private boolean top=true,animate=true,pending,accepted;
    private double zoom=1,panX,panY;private int viewportRight,viewportBottom;private long sentAt;
    private EditBox[] fields=new EditBox[7];private Button apply;private String status="";
    public BlueprintScreen(PointClient.View view){super(Component.translatable("mtrpoint.title"));this.view=view;draft=view.settings;}
    public String pointId(){return view.junction.id();}
    @Override protected void init(){
        viewportRight=Math.max(150,width-232);viewportBottom=height-52;int x=viewportRight+12;rowStep=Math.min(27,Math.max(19,(height-198)/7));int controls=80+rowStep*7;
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(page==0?"mtrpoint.tab_geometry":"mtrpoint.tab_profile"),b->{page=1-page;rebuildWidgets();}).bounds(x,38,208,20).build());
        for(int i=0;i<7;i++){
            int idx=page*7+i,y=80+i*rowStep;final int index=idx;
            EditBox field=new EditBox(font,x+91,y-4,75,20,Component.translatable("mtrpoint."+NAMES[idx]));field.setMaxLength(8);field.setValue(String.format(Locale.ROOT,"%.3f",draft.values()[idx]));
            field.setResponder(text->{try{PointSettings next=draft.with(index,Double.parseDouble(text));if(!next.equals(draft)){undo.push(draft);draft=next;view.preview(draft);}field.setTextColor(0xe9f5f6);}catch(RuntimeException ex){field.setTextColor(0xff7777);}});fields[i]=addRenderableWidget(field);
            addRenderableWidget(BlueprintButton.blueprint(Component.literal("−"),b->adjust(index,-STEP[index])).bounds(x+170,y-4,17,20).build());
            addRenderableWidget(BlueprintButton.blueprint(Component.literal("+"),b->adjust(index,STEP[index])).bounds(x+190,y-4,17,20).build());
        }
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(draft.movableFrog()?"mtrpoint.frog_movable":"mtrpoint.frog_fixed"),b->{change(draft.flags(!draft.movableFrog(),draft.enabled()));rebuildWidgets();}).bounds(x,controls,208,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(draft.enabled()?"mtrpoint.enabled":"mtrpoint.disabled"),b->{change(draft.flags(draft.movableFrog(),!draft.enabled()));rebuildWidgets();}).bounds(x,controls+24,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.profile"),b->cycleProfile()).bounds(x+108,controls+24,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable(top?"mtrpoint.isometric":"mtrpoint.top"),b->top=!top).bounds(16,38,92,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.test_motion"),b->{animate=false;double p=Double.isFinite(view.previewPosition)?view.previewPosition:view.position;view.previewPosition=p<.5?1:0;view.mesh=null;}).bounds(114,38,94,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.live"),b->{animate=true;view.previewPosition=Double.NaN;}).bounds(214,38,62,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.undo"),b->{if(!undo.isEmpty()){draft=undo.pop();view.preview(draft);rebuildWidgets();}}).bounds(16,height-36,72,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.reset"),b->{change(PointSettings.DEFAULT);rebuildWidgets();}).bounds(94,height-36,94,20).build());
        apply=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.apply"),b->save()).bounds(width-220,height-36,100,20).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("gui.cancel"),b->onClose()).bounds(width-112,height-36,100,20).build());
    }
    private void adjust(int index,double delta){try{change(draft.with(index,Math.round((draft.values()[index]+delta)*1000)/1000D));rebuildWidgets();}catch(IllegalArgumentException ignored){}}
    private void change(PointSettings next){undo.push(draft);draft=next;view.preview(draft);accepted=false;}
    private void cycleProfile(){List<String> ids=new ArrayList<>();ids.add("");ids.addAll(PointClient.styleIds(view.junction));int i=ids.indexOf(draft.profileStyle());change(draft.style(ids.get((i+1)%ids.size())));status=draft.profileStyle().isBlank()?"AUTO":draft.profileStyle();}
    private void save(){
        try{double[] values=draft.values();for(int i=0;i<fields.length;i++)values[page*7+i]=Double.parseDouble(fields[i].getValue());draft=PointSettings.of(values,draft.movableFrog(),draft.enabled(),draft.sleeperShifts(),draft.profileStyle());}catch(IllegalArgumentException ex){status="mtrpoint.invalid";return;}
        pending=true;sentAt=System.currentTimeMillis();status="mtrpoint.saving";V3 c=view.junction.center();
        PointNetwork.send(new PointNetwork.Edit(view.junction.id(),BlockPos.containing(c.x(),c.y(),c.z()),PointClient.revision(view.junction.id()),AppearanceData.JSON.toJson(draft)));
    }
    public void acknowledge(String id,String message){if(!id.equals(view.junction.id()))return;pending=false;status=message;accepted=message.equals("mtrpoint.saved");draft=PointClient.saved(id);view.preview(draft);rebuildWidgets();}
    @Override public void tick(){if(pending&&System.currentTimeMillis()-sentAt>5000){pending=false;status="mtrpoint.timeout";}if(apply!=null)apply.active=!pending;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){view.previewPosition=Double.NaN;view.preview(PointClient.saved(view.junction.id()));super.onClose();}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick){
        g.fill(0,0,width,height,0xff111d2b);g.fill(8,65,viewportRight-6,viewportBottom,0xff163044);
        g.enableScissor(8,65,viewportRight-6,viewportBottom);
        for(int x=12;x<viewportRight;x+=16)g.vLine(x,65,viewportBottom,0xff244455);
        for(int y=65;y<viewportBottom;y+=16)g.hLine(8,viewportRight-6,y,0xff244455);
        if(!animate)view.mesh=null;
        Mesh mesh=view.mesh();
        List<Mesh.Quad> quads=new ArrayList<>(mesh.quads);quads.sort(Comparator.comparingDouble(q->q.center().y()));
        for(Mesh.Quad q:quads){int color=switch(q.part()){case "sleeper"->q.index()==selectedSleeper?0xffffc467:0xff649499;case "blade"->0xffffc467;case "guard"->0xff89d8e5;default->0xffc9e4e9;};line(g,q.a(),q.b(),color);line(g,q.b(),q.c(),color);line(g,q.c(),q.d(),color);line(g,q.d(),q.a(),color);}
        g.disableScissor();
        g.drawString(font,title,16,14,0xffe4f5f5,false);
        g.drawString(font,Component.translatable(view.junction.kind()==Junction.Kind.Y?"mtrpoint.y":"mtrpoint.diamond"),viewportRight+12,16,0xfff1c56c,false);
        for(int i=0;i<7;i++)g.drawString(font,Component.translatable("mtrpoint."+NAMES[page*7+i]),viewportRight+12,80+i*rowStep,0xffbdd4dd,false);
        String profile=view.profile.source();g.drawString(font,font.plainSubstrByWidth(profile,205),viewportRight+12,80+rowStep*7+50,0xff89d8e5,false);
        String state=view.styles.isEmpty()?"mtrpoint.need_profile":view.state;
        g.drawString(font,Component.translatable(state),16,viewportBottom-30,0xfff1c56c,false);
        g.drawString(font,Component.translatable(selectedSleeper<0?"mtrpoint.help":"mtrpoint.drag_sleeper"),16,viewportBottom-16,0xffbdd4dd,false);
        if(!status.isBlank())g.drawString(font,Component.translatable(status),200,height-30,0xffe8ca82,false);
        super.render(g,mouseX,mouseY,partialTick);
    }
    private double scale(){double e=PointMesh.extent(view.junction,draft),w=view.junction.kind()==Junction.Kind.Y?view.junction.a().at(e).distance(view.junction.b().at(e))+view.profile.gauge()+2:2*e+3,h=view.junction.kind()==Junction.Kind.Y?e+3:2*e+3;if(!top){double d=w+h;w=d*.707;h=d*.35+2;}return Math.max(1,Math.min((viewportRight-35)/w,(viewportBottom-120)/h))*zoom;}
    private double[] project(V3 point){V3 c=view.junction.kind()==Junction.Kind.Y?view.junction.a().at(PointMesh.extent(view.junction,draft)/2):view.junction.center();V3 d=point.sub(c),forward=view.junction.a().tangent(view.junction.sa()),n=forward.lateral();double x=d.dot(n),z=d.dot(forward);return new double[]{viewportRight/2D+panX+(top?x:(x-z)*.707)*scale(),(65+viewportBottom)/2D+panY+(top?-z:(x+z)*.35-d.y()*1.8)*scale()};}
    private void line(GuiGraphics g,V3 a,V3 b,int color){double[] p=project(a),q=project(b);int steps=Math.max(1,(int)Math.max(Math.abs(q[0]-p[0]),Math.abs(q[1]-p[1])));for(int i=0;i<=steps;i++){int x=(int)Math.round(p[0]+(q[0]-p[0])*i/steps),y=(int)Math.round(p[1]+(q[1]-p[1])*i/steps);g.fill(x,y,x+1,y+1,color);}}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(x<viewportRight&&y>65&&y<viewportBottom){zoom=Math.max(.3,Math.min(6,zoom*Math.pow(1.15,delta)));return true;}return super.mouseScrolled(x,y,delta);}
    @Override public boolean mouseClicked(double x,double y,int button){if(x<viewportRight&&y>65&&y<viewportBottom&&button==0){double best=12;selectedSleeper=-1;for(var q:view.mesh().quads)if(q.part().equals("sleeper")){double[] p=project(q.center());double distance=Math.hypot(p[0]-x,p[1]-y);if(distance<best){best=distance;selectedSleeper=q.index();}}return true;}return super.mouseClicked(x,y,button);}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(x<viewportRight&&y>65&&y<viewportBottom){if(button==1){panX+=dx;panY+=dy;return true;}if(button==0&&selectedSleeper>=0){double old=draft.sleeperShifts().getOrDefault(selectedSleeper,0D);change(draft.sleeper(selectedSleeper,Math.max(-.25,Math.min(.25,old-dy/scale()))));return true;}}return super.mouseDragged(x,y,button,dx,dy);}
}
