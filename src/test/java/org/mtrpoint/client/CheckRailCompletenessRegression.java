package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.*;
import java.util.*;

/**
 * Final-presentation completeness regression for check rails.
 *
 * <p>The frame renderer never draws a view's own mesh as built. PointRenderer.drawGpu() calls
 * PointGpu.update() with removeGuards=true for every view, so that upload deletes every guard,
 * wing, rail, sleeper and fastener face the view owns; those faces have to come back from the
 * shared assembly mesh PointRenderer.assemble() builds. A view that owns no crossing request and
 * no scissors component is therefore only visible if assemble() re-supplies its check steel. This
 * test asserts the physical band of every expected check-rail run is covered in the final
 * presentation exactly once, whichever mesh supplies it.
 *
 * <p>The oracle is the run's own physical band after the intended clipping and merging, never a
 * part name: final baking relabels parts (a merged wing becomes a guard), so a name comparison
 * would fail on correct geometry. Each sample is a generic interior point of the band, away from
 * the band edges and from the .24 m sweep seams, where the face count is a property of the steel
 * rather than of the tessellation.
 */
public final class CheckRailCompletenessRegression {
    /** Longitudinal step, chosen off the .24 m rail-cell boundary. */
    private static final double LONGITUDINAL_STEP=.02;
    /** Lateral samples stay strictly inside the head band: a point exactly on the head edge is
     * covered by a different number of faces in each valid tessellation of the same steel. */
    private static final double EDGE_MARGIN=.008;
    private static final int LATERAL_STEPS=24;

    public static void run()throws Exception{
        for(boolean movable:new boolean[]{false,true}){
            PointSettings settings=PointSettings.DEFAULT.flags(movable,true);
            // A lone two-arm turnout is the worst case for a movable frog: it owns no crossing
            // request at all (DiamondGeometry.fixedY() is empty by design), so nothing but the
            // pooled check runs can re-supply its wing steel.
            expect("lone turnout",Regression.y(),settings);
            // A plain crossover: two opposite turnouts sharing one through road, no diamond.
            expect("crossover",crossover(),settings);
            // A real scissors crossing: each turnout is clipped to the shared region, so the
            // awaited band is the clipped interior interval, not the raw check run.
            for(boolean curved:new boolean[]{false,true})expect("scissors curved="+curved,Regression.scissors(curved),settings);
        }
    }

    private static void expect(String label,List<Track> roads,PointSettings settings)throws Exception{
        var junctions=Detector.find(roads);
        var groups=ScissorsLayout.find(junctions);
        var byId=new HashMap<String,ScissorsLayout>();
        for(var group:groups)for(var turnout:group.turnouts())byId.put(turnout.id(),group);
        var views=new ArrayList<PointClient.View>();
        var runs=new ArrayList<GuardRails.Run>();
        for(var junction:junctions){
            var group=byId.get(junction.id());
            var boundary=group!=null&&junction.kind()==Junction.Kind.Y?group.boundary(junction,settings):RailSampler.yBoundary(junction,settings);
            Mesh mesh=group!=null&&junction.kind()==Junction.Kind.DIAMOND?group.centerMesh(settings,Profile.STANDARD):PointMesh.build(junction,settings,Profile.STANDARD,0,boundary);
            if(group!=null&&junction.kind()!=Junction.Kind.DIAMOND)mesh=group.clip(mesh,junction,settings);
            views.add(PointRenderer.view(junction,settings,Profile.STANDARD,RailSampler.bank(mesh,junction,group==null?junction.tracks():group.tracks()),group));
            // A three-way fan is excluded from the pooled check runs by design; its guards are
            // owned by the view itself and are covered by the reference-face regression instead.
            if(group==null&&junction.kind()==Junction.Kind.THREE)continue;
            for(var original:GuardRails.forJunction(junction,settings,Profile.STANDARD)){
                var run=original;
                if(group!=null){
                    boolean before=junction.center().sub(group.crossing().center()).dot(group.axis())<0;
                    run=run.clip(group.crossing().center().add(group.axis().mul(before?group.lo():group.hi())),group.axis().mul(before?1:-1));
                }
                if(run!=null)runs.add(run);
            }
        }
        require(!runs.isEmpty(),label+": fixture produced no check runs");
        Mesh assembly=PointRenderer.assembleForTest(views);
        var assemblyTops=tops(assembly);
        var ownTops=tops(presented(views));
        Profile profile=Profile.STANDARD.tune(settings);
        int samples=0;var missed=new ArrayList<String>();var duplicated=new ArrayList<String>();
        for(var run:runs){
            // Interior interval only: the terminal flare windows of a run are owned by the
            // adjacent merged steel and are not part of its physical check band.
            for(double d=run.start()+.55;d<run.end()-.55;d+=LONGITUDINAL_STEP)for(int step=0;step<=LATERAL_STEPS;step++){
                double lateral=-(profile.headWidth()/2-EDGE_MARGIN)+step*(profile.headWidth()-2*EDGE_MARGIN)/LATERAL_STEPS;
                V3 point=run.point(d).add(run.road().tangent(d).lateral().mul(lateral));
                int got=coverage(assemblyTops,point)+coverage(ownTops,point);
                samples++;
                if(got<1&&missed.size()<6)missed.add(String.format(Locale.ROOT,"%s@%.3f lateral=%+.4f",run.road().id,d,lateral));
                if(got>1&&duplicated.size()<6)duplicated.add(String.format(Locale.ROOT,"%s@%.3f lateral=%+.4f count=%d",run.road().id,d,lateral,got));
            }
        }
        require(samples>400,label+" movable="+settings.movableFrog()+": fixture too thin to prove anything: "+samples);
        require(missed.isEmpty(),label+" movable="+settings.movableFrog()+": final presentation is missing check-rail steel at "+missed);
        require(duplicated.isEmpty(),label+" movable="+settings.movableFrog()+": final presentation duplicates check-rail steel at "+duplicated);
        System.out.println("PASS: "+label+" movable="+settings.movableFrog()+" keeps "+samples+" check-rail band samples exactly once");
    }

    /** Exactly the faces PointGpu.update() keeps for these views: drawGpu always passes
     * removeGuards=true, and a fixed shared crossing also drops the view's own frog steel. */
    private static Mesh presented(List<PointClient.View> views){
        Mesh out=new Mesh();
        for(var view:views){
            boolean movable=view.settings.movableFrog();
            boolean removeSharedCrossing=view.scissors==null&&(view.junction.kind()==Junction.Kind.DIAMOND||view.junction.kind()==Junction.Kind.Y&&!movable);
            for(var quad:view.mesh.quads){
                if(quad.part().equals("guard")||quad.part().equals("sleeper")||quad.part().equals("fastener")||quad.part().equals("wing")||quad.part().equals("rail"))continue;
                if(removeSharedCrossing&&quad.part().equals("frog"))continue;
                out.quad(quad);
            }
        }
        return out;
    }

    /** Flat, finite upper faces at the working rail top; the coverage mask. */
    private static List<Mesh.Quad> tops(Mesh mesh){
        double top=Profile.STANDARD.top();
        var result=new ArrayList<Mesh.Quad>();
        for(var quad:mesh.quads){
            boolean flat=true;
            for(V3 vertex:List.of(quad.a(),quad.b(),quad.c(),quad.d()))
                if(!Double.isFinite(vertex.x()+vertex.y()+vertex.z())||Math.abs(vertex.y()-top)>1e-8){flat=false;break;}
            if(flat)result.add(quad);
        }
        return result;
    }
    private static int coverage(List<Mesh.Quad> faces,V3 point){
        int count=0;for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))count++;return count;
    }
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}

    /** A plain crossover: two opposite turnouts sharing one through road. */
    private static List<Track> crossover(){
        Track main=Regression.line("x-main","m0","m1",new V3(0,0,-30),new V3(0,0,30));
        var up=new ArrayList<V3>();var down=new ArrayList<V3>();
        for(int i=0;i<=120;i++){
            double z=-30+i*.5,bend=Math.max(0,z-5),other=Math.max(0,-z-5);
            up.add(new V3(.008*bend*bend,0,z));down.add(new V3(-.008*other*other,0,z));
        }
        return List.of(main,new Track("x-up","m0","ua",up),new Track("x-down","m1","da",down));
    }
}
