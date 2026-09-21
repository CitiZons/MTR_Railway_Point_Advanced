package org.mtrpoint.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.mtrpoint.*;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * Unified complex-junction editor: the whole current assembly stays visible, clicking a
 * turnout selects it in place and the right-hand panel edits that turnout without leaving the
 * plan. Sleeper and guard interaction stays global even when no turnout marker is selected.
 */
public final class PointSelectionScreen extends Screen implements TurnoutPanel.Host {
    private record SleeperPick(int global,PointClient.View view,int local,SleeperHandles.Handle handle) {V3 center(){return handle.center();}}
    private record GuardPick(int global,PointClient.View view,int local,GuardRails.Run run) {}
    private record PlanEdit(Map<PointClient.View,PointSettings> before) {}
    private final List<PointClient.View> points;
    private final Map<PointClient.View,PointSettings> drafts=new IdentityHashMap<>();private final Set<PointClient.View> dirty=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Deque<PlanEdit> undo=new ArrayDeque<>();
    private Mesh plan=new Mesh();private List<SleeperPick> sleepers=List.of();private List<GuardPick> guards=List.of();private List<GuardRails.Run> mergedGuards=List.of();private List<Set<Integer>> mergedOwners=List.of();private final Set<Integer> selectedGuards=new LinkedHashSet<>();private final BlueprintWire wire=new BlueprintWire();
    private Mesh highlight,guardHighlight=new Mesh();private int highlighted=-1;private final BlueprintWire highlightWire=new BlueprintWire();
    private V3 center;private double zoom=5,panX,panY,uiScale=1;private int selected,right,bottom,uiWidth,uiHeight,uiHeightStrip;
    private int selectedSleeper=-1;private boolean framed;private final TurnoutPanel panel=new TurnoutPanel(this);
    private PointClient.View sleeperView;private int sleeperIndex=-1;private long sentAt;private String status="";
    private boolean pending,numbersVisible=true,isolateSleepers,planDirty;private Button saveSleepers,undoSleepers,toggleNumbers,toggleSleeperIsolation,guardStartMinus,guardStartPlus,guardEndMinus,guardEndPlus,guardMerge,guardReset;
    private final Deque<PointClient.View> saveQueue=new ArrayDeque<>();private PointClient.View savingView;
    public PointSelectionScreen(){
        super(Component.translatable("mtrpoint.select_title"));var mc=net.minecraft.client.Minecraft.getInstance();
        center=new V3(mc.player.getX(),mc.player.getY(),mc.player.getZ());
        points=PointClient.views.stream().filter(v->v.junction.center().distance(center)<64).sorted(Comparator.comparing(v->v.junction.id())).toList();
        points.forEach(v->drafts.put(v,v.settings));
        // Highlight the point nearest the gaze ray, not merely the player's feet.
        var look=mc.player.getLookAngle();V3 eye=new V3(mc.player.getX(),mc.player.getEyeY(),mc.player.getZ()),dir=new V3(look.x,look.y,look.z);double best=Double.MAX_VALUE;
        for(int i=0;i<points.size();i++){V3 delta=PointClient.editCenter(points.get(i)).sub(eye);double along=Math.max(0,delta.dot(dir)),score=delta.sub(dir.mul(along)).length();if(score<best){selected=i;best=score;}}
        preparePlan();
    }
    @Override protected void init(){
        uiScale=PointUiScale.effective(width,height);uiWidth=(int)Math.floor(width/uiScale);uiHeight=(int)Math.floor(height/uiScale);
        right=Math.max(150,uiWidth-224);bottom=uiHeight-72;uiHeightStrip=uiHeight-30;
        if(!framed&&!points.isEmpty()){
            double x0=Double.MAX_VALUE,x1=-Double.MAX_VALUE,z0=x0,z1=x1;
            Collection<V3> frame=sleepers.isEmpty()?points.stream().map(PointClient::editCenter).toList():sleepers.stream().map(SleeperPick::center).toList();
            for(V3 p:frame){x0=Math.min(x0,p.x());x1=Math.max(x1,p.x());z0=Math.min(z0,p.z());z1=Math.max(z1,p.z());}
            center=new V3((x0+x1)/2,center.y(),(z0+z1)/2);
            zoom=Math.max(.5,Math.min(5,Math.min((right-40)/(x1-x0+16),(bottom-95)/(z1-z0+16))));framed=true;
        }
        reveal();
        panel.init(uiWidth-216,34,80,bottom,uiHeightStrip,uiHeight,2);
        undoSleepers=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.undo"),b->undoLast()).bounds(12,uiHeight-56,84,22).build());
        saveSleepers=addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.select_save_sleeper"),b->apply()).bounds(104,uiHeight-56,88,22).build());
        addRenderableWidget(BlueprintButton.blueprint(Component.translatable("mtrpoint.select_close"),b->onClose()).bounds(12,uiHeightStrip,90,22).build());
        if(points.size()>1){
            toggleNumbers=addRenderableWidget(BlueprintButton.blueprint(numbersLabel(),b->{numbersVisible=!numbersVisible;rebuildWidgets();}).bounds(110,uiHeightStrip,108,22).build());
        }else toggleNumbers=null;
        toggleSleeperIsolation=addRenderableWidget(BlueprintButton.blueprint(sleeperIsolationLabel(),b->{isolateSleepers=!isolateSleepers;selectedSleeper=-1;sleeperView=null;sleeperIndex=-1;panel.selectedSleeper=-1;preparePlan();updateButtons();}).bounds(226,uiHeightStrip,126,22).build());
        guardStartMinus=guardButton("mtrpoint.guard_start_minus",12,76,b->adjustGuards(-.1,0));
        guardStartPlus=guardButton("mtrpoint.guard_start_plus",106,76,b->adjustGuards(.1,0));
        guardEndMinus=guardButton("mtrpoint.guard_end_minus",12,100,b->adjustGuards(0,-.1));
        guardEndPlus=guardButton("mtrpoint.guard_end_plus",106,100,b->adjustGuards(0,.1));
        guardMerge=guardButton("mtrpoint.guard_merge",12,124,b->mergeGuards());
        guardReset=guardButton("mtrpoint.guard_reset",106,124,b->resetGuards());
        panel.sync();updateButtons();
    }
    private Button guardButton(String key,int x,int y,Button.OnPress action){return addRenderableWidget(BlueprintButton.blueprint(Component.translatable(key),action).bounds(x,y,88,20).build());}
    /** Complex/multi-junction layouts only; the toggle reads 隐藏编号 while numbers are shown. */
    private Component numbersLabel(){return Component.translatable(numbersVisible?"mtrpoint.numbers_hide":"mtrpoint.numbers_show");}
    private Component sleeperIsolationLabel(){return Component.translatable(isolateSleepers?"mtrpoint.sleeper_isolation_cancel":"mtrpoint.sleeper_isolate");}
    private PointClient.View selectedView(){return points.isEmpty()?null:points.get(selected);}
    private PointClient.View byId(String id){return id==null?null:points.stream().filter(v->v.junction.id().equals(id)).findFirst().orElse(null);}
    @Override public PointClient.View junction(){return selectedView();}
    @Override public PointSettings draft(String id){PointClient.View view=byId(id);return view==null?PointSettings.DEFAULT:drafts.getOrDefault(view,view.settings);}
    @Override public void changeDraft(String id,PointSettings next){
        PointClient.View view=byId(id);if(view==null)return;PointSettings before=drafts.get(view);if(next.equals(before))return;
        if(undo.size()>=100)undo.removeLast();undo.push(new PlanEdit(Map.of(view,before)));
        drafts.put(view,next);dirty.add(view);view.preview(next);planDirty=true;status="mtrpoint.preview_changed";updateButtons();
    }
    /** The panel documents that an EditBox responder never asks for an immediate preview, so typing
     * only marks the plan dirty and the next frame rebuilds it once. A discrete action (button,
     * click, added sleeper) calls this and still sees the rebuilt plan in the same event. Rebuilding
     * per keystroke and per drag event re-baked every view, re-ran the guard merge and re-hashed the
     * whole wire preview each time, which is what made the editor stutter on dense layouts. */
    @Override public void previewNow(){if(planDirty){planDirty=false;preparePlan();}}
    @Override public void status(String key){status=key;}
    @Override public void addWidget(AbstractWidget widget){addRenderableWidget(widget);}
    @Override public void refreshWidgets(){rebuildWidgets();}
    @Override public Font fontRenderer(){return font;}
    @Override public void apply(){
        if(pending||dirty.isEmpty())return;saveQueue.clear();saveQueue.addAll(points.stream().filter(dirty::contains).toList());sendNext();
    }
    private void sendNext(){savingView=saveQueue.poll();if(savingView==null){pending=false;status="mtrpoint.saved";updateButtons();return;}PointSettings draft=drafts.get(savingView);V3 c=savingView.junction.center();pending=true;sentAt=System.currentTimeMillis();status="mtrpoint.saving";PointNetwork.send(new PointNetwork.Edit(savingView.junction.id(),BlockPos.containing(c.x(),c.y(),c.z()),PointClient.revision(savingView.junction.id()),AppearanceData.JSON.toJson(draft)));updateButtons();}
    private void preparePlan(){
        Mesh next=new Mesh();var picks=new ArrayList<SleeperPick>();var guardPicks=new ArrayList<GuardPick>();var pool=new ArrayList<GuardRails.Run>();var poolOwners=new ArrayList<Set<Integer>>();
        for(int point=0;point<points.size();point++){var view=points.get(point);int base=(point+1)*1024;
            for(var q:view.mesh().quads)if(!q.part().equals("sleeper")&&!q.part().equals("fastener"))next.quad(q);
            for(var handle:SleeperHandles.collect(view.mesh())){int global=base+handle.index();var faces=new ArrayList<Mesh.Quad>();
                for(var face:handle.faces()){var mapped=new Mesh.Quad(face.a(),face.b(),face.c(),face.d(),face.surface(),face.part(),global,face.uv(),face.rail());next.quad(mapped);faces.add(mapped);}
                if(!isolateSleepers)picks.add(new SleeperPick(global,view,handle.index(),new SleeperHandles.Handle(global,handle.center(),List.copyOf(faces))));
            }
            // One enumerator for every kind: a plain crossing and a three-way fan own their check
            // steel inside their shared assembly, and a scissors centre is drawn by its turnouts,
            // so forJunction() alone left the editor with no selectable rail on those layouts.
            var settings=drafts.get(view);
            var boundary=view.scissors!=null&&view.junction.kind()==Junction.Kind.Y?view.scissors.boundary(view.junction,settings):RailSampler.yBoundary(view.junction,settings);
            var local=GuardRails.selectable(view.junction,settings,view.profile,view.scissors,boundary);
            for(int i=0;i<local.size();i++){guardPicks.add(new GuardPick(base+i,view,i,local.get(i)));var shown=clipToScissors(view,local.get(i));if(shown!=null){pool.add(shown);poolOwners.add(Set.of(base+i));}}
        }
        // Guards are pooled and merged exactly like the world assembly, so a manual group is drawn
        // as the single rail it will become instead of one run per view with two interior mouths.
        // Only the cheap merge is shared here; the quadratic surface union stays out of this path.
        var owners=new ArrayList<Set<Integer>>();mergedGuards=GuardRails.exposeEnds(GuardRails.merge(pool,owners));
        // merge() reports provenance as indices into the pool, while a selection holds the global
        // pick indices, so the two must be translated or nothing ever matches and no guard lights up.
        var globals=new ArrayList<Set<Integer>>(owners.size());
        for(var set:owners){var mapped=new LinkedHashSet<Integer>();for(int index:set)if(index>=0&&index<poolOwners.size())mapped.addAll(poolOwners.get(index));globals.add(Set.copyOf(mapped));}
        mergedOwners=List.copyOf(globals);
        for(var run:mergedGuards)if(run.end()>run.start()+1e-7)for(var quad:run.mesh().quads)next.quad(quad);
        // This is a cached editor mesh. Do not run the quadratic surface union here: overlapping
        // turnout views are harmless in a wire preview and rebuilding that union caused the
        // large pauses/memory spikes reported for dense scissors layouts.
        plan=next;sleepers=isolateSleepers?List.of():List.copyOf(picks);guards=List.copyOf(guardPicks);selectedGuards.retainAll(guards.stream().map(GuardPick::global).toList());guardHighlight=new Mesh();
        for(int i=0;i<mergedGuards.size();i++)if(guardRunSelected(i)){var run=mergedGuards.get(i);for(var q:run.mesh().quads)guardHighlight.quad(new Mesh.Quad(q.a(),q.b(),q.c(),q.d(),q.surface(),"blade",-1,q.uv(),q.rail()));}
    }
    /** The shared-region clip the world assembly applies, so a scissors preview matches it. */
    private static GuardRails.Run clipToScissors(PointClient.View view,GuardRails.Run run){
        var group=view.scissors;if(group==null)return run;
        boolean before=view.junction.center().sub(group.crossing().center()).dot(group.axis())<0;
        return run.clip(group.crossing().center().add(group.axis().mul(before?group.lo():group.hi())),group.axis().mul(before?1:-1));
    }
    /** True when a merged run is the union of at least one selected pick. The match is by
     * provenance, not by geometry: at a shallow crossing two differently oriented check rails run
     * within the merge tolerance of each other, so a proximity test lit up rails the user never
     * clicked. Only a run the selected pick was actually merged into counts. */
    private boolean guardRunSelected(int index){
        if(index<0||index>=mergedOwners.size())return false;
        for(int owner:mergedOwners.get(index))if(selectedGuards.contains(owner))return true;
        return false;
    }
    private Component label(int i){var v=points.get(i);return Component.literal((i+1)+" · ").append(Component.translatable(kind(v)));}
    private static String kind(PointClient.View v){return v.junction.kind()==Junction.Kind.Y?"mtrpoint.y":v.junction.kind()==Junction.Kind.THREE?"mtrpoint.three":"mtrpoint.diamond";}
    private double[] project(V3 p){return new double[]{right/2D+(p.x()-center.x())*zoom+panX,(65+bottom)/2D+(p.z()-center.z())*zoom+panY};}
    private void reveal(){if(points.isEmpty())return;double[] p=project(PointClient.editCenter(points.get(selected)));panX+=Math.max(25,Math.min(right-25,p[0]))-p[0];panY+=Math.max(85,Math.min(bottom-20,p[1]))-p[1];}
    /** In-place selection: the plan viewport and the whole assembly stay untouched. */
    private void selectPoint(int index){
        if(index<0||index>=points.size())return;
        selected=index;selectedSleeper=-1;sleeperView=null;sleeperIndex=-1;panel.selectedSleeper=-1;
        panel.actionMenu=false;panel.addingSleeper=false;reveal();rebuildWidgets();
    }
    private void undoLast(){
        if(undo.isEmpty()||pending)return;PlanEdit edit=undo.pop();
        for(var entry:edit.before.entrySet()){PointClient.View view=entry.getKey();PointSettings before=entry.getValue();drafts.put(view,before);if(before.equals(PointClient.saved(view.junction.id())))dirty.remove(view);else dirty.add(view);view.preview(before);sleeperView=view;}
        status="mtrpoint.preview_changed";preparePlan();panel.sync();updateButtons();
    }
    public void acknowledge(String id,String message){
        PointClient.View view=byId(id);if(view==null)return;
        status=message;PointSettings saved=PointClient.saved(id);drafts.put(view,saved);dirty.remove(view);view.preview(saved);preparePlan();panel.sync();if(savingView==view&&!saveQueue.isEmpty()){sendNext();return;}pending=false;savingView=null;updateButtons();
    }
    private void updateButtons(){
        boolean applyable=!dirty.isEmpty();
        if(saveSleepers!=null)saveSleepers.active=applyable&&!pending;
        if(undoSleepers!=null)undoSleepers.active=!undo.isEmpty()&&!pending;
        if(toggleNumbers!=null)toggleNumbers.setMessage(numbersLabel());
        if(toggleSleeperIsolation!=null)toggleSleeperIsolation.setMessage(sleeperIsolationLabel());
        panel.applyActive(applyable&&!pending);
        boolean show=!selectedGuards.isEmpty();for(Button b:List.of(guardStartMinus,guardStartPlus,guardEndMinus,guardEndPlus,guardMerge,guardReset))if(b!=null){b.visible=show;b.active=show&&!pending;}
        if(guardMerge!=null)guardMerge.active=selectedGuards.size()>1&&!pending;
    }
    private List<GuardPick> selectedGuardPicks(){return guards.stream().filter(g->selectedGuards.contains(g.global)).toList();}
    private void editGuards(java.util.function.Function<GuardPick,PointSettings.GuardEdit> edits){
        var selected=selectedGuardPicks();if(selected.isEmpty()||pending)return;var before=new IdentityHashMap<PointClient.View,PointSettings>();
        for(var pick:selected){before.putIfAbsent(pick.view,drafts.get(pick.view));PointSettings current=drafts.get(pick.view);PointSettings.GuardEdit edit=edits.apply(pick);drafts.put(pick.view,current.guard(pick.local,edit));dirty.add(pick.view);}
        if(undo.size()>=100)undo.removeLast();undo.push(new PlanEdit(Map.copyOf(before)));for(var view:before.keySet())view.preview(drafts.get(view));status="mtrpoint.preview_changed";preparePlan();updateButtons();
    }
    private void adjustGuards(double startDelta,double endDelta){editGuards(p->{var r=p.run;double start=Math.max(0,Math.min(r.end()-.05,r.start()+startDelta)),end=Math.min(r.road().length,Math.max(start+.05,r.end()+endDelta));return new PointSettings.GuardEdit(start,end,r.flareStart(),r.flareEnd(),r.mergeGroup());});}
    private void resetGuards(){editGuards(p->null);}
    /** Every selectable check rail may be grouped; the editor no longer refuses a selection that
     * cannot become one drawn rail, it reports it. The union extent is resolved on each run's own
     * road, and only the outer ends of the union keep a mouth, so a multi-source seam disappears. */
    private void mergeGuards(){
        var selected=selectedGuardPicks();if(selected.size()<2)return;
        String group="manual-"+Integer.toUnsignedString(selected.stream().map(g->g.view.junction.id()+":"+g.local).sorted().toList().hashCode(),36);
        var ends=new ArrayList<V3>();
        for(var pick:selected){ends.add(pick.run.point(pick.run.start()));ends.add(pick.run.point(pick.run.end()));}
        var edits=new HashMap<Integer,PointSettings.GuardEdit>();
        for(var pick:selected){
            double start=Double.MAX_VALUE,end=-Double.MAX_VALUE;
            for(V3 point:ends){double at=pick.run.road().nearest(point);start=Math.min(start,at);end=Math.max(end,at);}
            start=Math.max(0,start);end=Math.min(pick.run.road().length,end);
            if(end<=start+.05){status="mtrpoint.guard_merge_rejected";return;}
            V3 first=pick.run.road().at(start),last=pick.run.road().at(end);
            // Only the outer ends of the union keep a mouth; an end that another selected run
            // reaches was an interior seam and must lose its flare.
            boolean flareStart=false,flareEnd=false;
            for(var other:selected){
                if(other.run.flareStart()&&other.run.point(other.run.start()).distance(first)<.2)flareStart=true;
                if(other.run.flareEnd()&&other.run.point(other.run.end()).distance(last)<.2)flareEnd=true;
            }
            edits.put(pick.global,new PointSettings.GuardEdit(start,end,flareStart,flareEnd,group));
        }
        editGuards(p->edits.getOrDefault(p.global,new PointSettings.GuardEdit(p.run.start(),p.run.end(),p.run.flareStart(),p.run.flareEnd(),p.run.mergeGroup())));
        // A rejected union already returned above, and the field starts as an empty string, so the
        // old "status != null" guard here was always true: the merge never reported its outcome.
        int joined=0;for(int i=0;i<mergedGuards.size();i++)if(guardRunSelected(i))joined++;
        status=joined<=1?"mtrpoint.guard_merged":"mtrpoint.guard_merge_partial";
    }
    private GuardPick guardAt(double x,double y){GuardPick hit=null;double best=12;for(var guard:guards){int count=Math.max(4,(int)Math.ceil((guard.run.end()-guard.run.start())/.2));double[] previous=project(guard.run.point(guard.run.start()));for(int i=1;i<=count;i++){double[] next=project(guard.run.point(guard.run.start()+(guard.run.end()-guard.run.start())*i/count));double distance=segmentDistance(x,y,previous[0],previous[1],next[0],next[1]);if(distance<best){best=distance;hit=guard;}previous=next;}}return hit;}
    private static double segmentDistance(double x,double y,double ax,double ay,double bx,double by){double dx=bx-ax,dy=by-ay,length=dx*dx+dy*dy,t=length<1e-9?0:Math.max(0,Math.min(1,((x-ax)*dx+(y-ay)*dy)/length));return Math.hypot(x-ax-dx*t,y-ay-dy*t);}
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
        if(planDirty){planDirty=false;preparePlan();}
        int mx=(int)(x/uiScale),my=(int)(y/uiScale);g.pose().pushPose();g.pose().scale((float)uiScale,(float)uiScale,1);
        g.fill(0,0,uiWidth,uiHeight,0xff111d2b);g.fill(8,65,right-6,bottom,0xff163044);g.drawString(font,title,12,12,0xffe4f5f5,false);
        g.drawString(font,Component.translatable(selectedSleeper<0?"mtrpoint.select_help":"mtrpoint.select_drag_sleeper"),12,38,0xffbdd4dd,false);
        g.enableScissor(8,65,right-6,bottom);wire.draw(g,plan,0,this::project,selectedSleeper,right,bottom);
        highlight();if(highlight!=null)highlightWire.draw(g,highlight,0,this::project,-1,right,bottom);if(!guardHighlight.quads.isEmpty())highlightWire.draw(g,guardHighlight,0,this::project,-1,right,bottom);
        int hovered=numbersVisible?markerAt(mx,my):-1;
        if(numbersVisible)for(int i=0;i<points.size();i++){double[] p=project(PointClient.editCenter(points.get(i)));int px=(int)p[0],py=(int)p[1];if(px<17||px>right-15||py<74||py>bottom-9)continue;
            boolean active=i==selected;int color=active?0xffffc467:i==hovered?0xffb7e8f2:0xff89d8e5;
            g.fill(px-7,py-7,px+8,py+8,active?0xff2a4257:0xff111d2b);
            g.drawCenteredString(font,Integer.toString(i+1),px,py-4,color);
            if(active){g.hLine(px-9,px+9,py-9,color);g.hLine(px-9,px+9,py+9,color);g.vLine(px-9,py-9,py+9,color);g.vLine(px+9,py-9,py+9,color);}
        }
        g.disableScissor();
        renderChips(g,mx,my);
        if(points.isEmpty())g.drawString(font,Component.translatable("mtrpoint.no_point"),12,80,0xffffc467,false);
        else {var v=selectedView();var p=PointClient.editCenter(v);
            Component line=label(selected).copy().append(Component.literal(String.format(Locale.ROOT,"   X %.1f  Z %.1f",p.x(),p.z())));
            g.drawString(font,status.isBlank()?line:Component.translatable(status),12,52,status.isBlank()?0xffffc467:0xffe8ca82,false);}
        super.render(g,mx,my,delta);
        panel.render(g,mx,my);
        g.pose().popPose();
    }
    private static final int CHIP_WIDTH=26,CHIP_HEIGHT=20,CHIP_GAP=3,CHIP_LEFT=12;
    private int chipsPerRow(){return Math.max(1,(right-CHIP_LEFT-8)/(CHIP_WIDTH+CHIP_GAP));}
    private int[] chipRect(int index){int perRow=chipsPerRow(),row=index/perRow,column=index%perRow;return new int[]{CHIP_LEFT+column*(CHIP_WIDTH+CHIP_GAP),bottom-CHIP_HEIGHT-2-row*(CHIP_HEIGHT+CHIP_GAP)};}
    /** Compact numbered navigation strip; in-place selection remains the primary interaction. */
    private void renderChips(GuiGraphics g,int mouseX,int mouseY){
        if(points.size()<2||!numbersVisible)return;
        for(int i=0;i<points.size();i++){int[] r=chipRect(i);boolean hover=mouseX>=r[0]&&mouseX<r[0]+CHIP_WIDTH&&mouseY>=r[1]&&mouseY<r[1]+CHIP_HEIGHT;
            int edge=i==selected?0xffffc467:hover?0xff8fb6c4:0xff3d5a68;
            g.fill(r[0],r[1],r[0]+CHIP_WIDTH,r[1]+CHIP_HEIGHT,edge);
            g.fill(r[0]+1,r[1]+1,r[0]+CHIP_WIDTH-1,r[1]+CHIP_HEIGHT-1,hover?0xff2a4a5c:0xff163044);
            g.drawCenteredString(font,Integer.toString(i+1),r[0]+CHIP_WIDTH/2,r[1]+6,i==selected?0xffffc467:0xffcfe6ee);
        }
    }
    private int markerAt(double x,double y){
        int hit=-1;double best=14;for(int i=0;i<points.size();i++){double[] p=project(PointClient.editCenter(points.get(i)));double d=Math.hypot(x-p[0],y-p[1]);if(d<best){hit=i;best=d;}}return hit;
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        double rawX=x/uiScale,rawY=y/uiScale;
        if(super.mouseClicked(rawX,rawY,button))return true;
        // The numbered strip floats over the plan, so it must win over the map click.
        if(button==0&&numbersVisible&&points.size()>1)for(int i=0;i<points.size();i++){int[] r=chipRect(i);if(rawX>=r[0]&&rawX<r[0]+CHIP_WIDTH&&rawY>=r[1]&&rawY<r[1]+CHIP_HEIGHT){selectPoint(i);return true;}}
        if(button==0&&rawX<right&&rawY>=65&&rawY<bottom){
            GuardPick guard=guardAt(rawX,rawY);if(guard!=null){if(!hasControlDown())selectedGuards.clear();if(!selectedGuards.add(guard.global)&&hasControlDown())selectedGuards.remove(guard.global);selectedSleeper=-1;sleeperView=null;sleeperIndex=-1;selected=points.indexOf(guard.view);preparePlan();panel.sync();updateButtons();return true;}else if(!hasControlDown()){selectedGuards.clear();guardHighlight=new Mesh();updateButtons();}
            SleeperHandles.Handle hit=SleeperHandles.hit(sleepers.stream().map(SleeperPick::handle).toList(),this::project,rawX,rawY,5);
            SleeperPick picked=hit==null?null:sleepers.stream().filter(v->v.global==hit.index()).findFirst().orElse(null);
            if(picked!=null){selectedSleeper=picked.global;sleeperView=picked.view;sleeperIndex=picked.local;selected=points.indexOf(picked.view);panel.selectedSleeper=picked.local;updateButtons();return true;}
            int pointHit=markerAt(rawX,rawY);if(pointHit>=0){selectPoint(pointHit);return true;}
            return true;
        }
        if(panel.mouseClicked(rawX,rawY,button)){updateButtons();return true;}
        return false;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        x/=uiScale;y/=uiScale;dx/=uiScale;dy/=uiScale;
        if(button==1&&x<right){panX+=dx;panY+=dy;return true;}
        if(button==0&&x<right&&selectedSleeper>=0&&sleeperView!=null){
            SleeperPick pick=sleepers.stream().filter(v->v.global==selectedSleeper).findFirst().orElse(null);if(pick==null)return true;
            List<Track> roads=TurnoutPanel.sleeperTracks(sleeperView);Track road=roads.stream().min(Comparator.comparingDouble(v->v.at(v.nearest(pick.center())).distance(pick.center()))).orElse(sleeperView.junction.a());V3 tangent=road.tangent(road.nearest(pick.center()));double shift=(dx*tangent.x()+dy*tangent.z())/zoom;
            PointSettings draft=drafts.get(sleeperView);double old=draft.sleeperShifts().getOrDefault(sleeperIndex,0D),value=Math.max(-.25,Math.min(.25,old+shift));
            changeDraft(sleeperView.junction.id(),draft.sleeper(sleeperIndex,value));return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){x/=uiScale;y/=uiScale;if(x<right){double worldX=(x-right/2D-panX)/zoom,worldZ=(y-(65+bottom)/2D-panY)/zoom;double next=Math.max(.5,Math.min(40,zoom*Math.pow(1.2,delta)));panX=x-right/2D-worldX*next;panY=y-(65+bottom)/2D-worldZ*next;zoom=next;return true;}return super.mouseScrolled(x,y,delta);}
    @Override public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(x/uiScale,y/uiScale,button);}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(panel.keyPressed(key)){updateButtons();return true;}
        if(key==GLFW.GLFW_KEY_ENTER){openBlueprint();return true;}
        if(key==GLFW.GLFW_KEY_TAB&&!points.isEmpty()){selectPoint((selected+(hasShiftDown()?points.size()-1:1))%points.size());return true;}
        return super.keyPressed(key,scan,mods);
    }
    /** Explicit escape hatch to the isolated blueprint; clicking a turnout never navigates. */
    private void openBlueprint(){PointClient.View view=selectedView();if(view!=null)minecraft.setScreen(new BlueprintScreen(view));}
    @Override public void tick(){if(pending&&System.currentTimeMillis()-sentAt>5000){pending=false;status="mtrpoint.timeout";updateButtons();}}
    @Override public void removed(){panel.addingSleeper=false;for(var view:points)view.preview(PointClient.saved(view.junction.id()));super.removed();}
}
