package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Asymmetric four-way crossings (scissors / double slips).
 *
 *  A symmetric scissors is the positive baseline: its two diagonals cross exactly at the centre of
 *  its shared crossing, so every rail-head intersection in the shared region belongs to one
 *  junction and one shared mesh. An asymmetric layout - unequal corner nodes, skewed road angles,
 *  curved through roads - makes the diagonals cross at an extra interior node, and that junction
 *  used to build a second view of the very same rails. Because a view only ever cuts its own
 *  flangeways, the extra copy stayed solid inside the shared channels: the reported "missed cuts"
 *  that appear in some directions and not others.
 *
 *  The checks run on the final world mesh: the shared component assembly plus every per-view mesh,
 *  filtered exactly as the frame renderer filters them. */
public final class ScissorsGeometryRegression {
    private record Fixture(String name,List<Track> roads,boolean extraInterior){}
    private static int seamViewSamples;
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}

    public static void run()throws Exception{
        int absorbed=0,cells=0;
        for(Fixture f:fixtures()){
            Profile raw=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;Profile p=raw.tune(s);double top=p.top()+s.verticalOffset();
            var junctions=Detector.find(f.roads());
            var groups=ScissorsLayout.find(junctions);
            require(groups.size()==1,f.name()+": one four-node scissors expected, got "+groups.size());
            var group=groups.get(0);
            var views=PointRenderer.viewsForTest(junctions,s,raw);
            Mesh world=PointRenderer.worldForTest(views);
            var perView=new ArrayList<List<Mesh.Quad>>();
            var perViewHeads=new ArrayList<List<Mesh.Quad>>();
            for(var v:views){var list=new ArrayList<Mesh.Quad>();for(var q:v.mesh().quads)if(!PointGpu.hiddenInView(q))list.add(q);perView.add(list);perViewHeads.add(headTopsOf(list,top));}
            var heads=headTops(world,top);
            require(heads.size()>200,f.name()+": the world mesh has to carry the running steel");
            // No steel may survive inside a shared flangeway: that is the reported under-cut.
            int blocked=blockedCentral(f.roads(),group,heads,s,p);StringBuilder where=new StringBuilder();
            if(blocked>0)where.append(describe(f.roads(),group,heads,s,p));
            if(f.extraInterior()){
                require(views.size()<junctions.size(),f.name()+": the shared centre has to replace the interior crossing it already draws (views="+views.size()+"/"+junctions.size()+", steel in shared flangeways="+blocked+")");
                absorbed+=junctions.size()-views.size();
            }
            require(blocked==0,f.name()+": steel sits in a shared flangeway ("+blocked+" samples)"+where);
            cells+=checkSeam(f.name(),group,views,perView,heads,s,p,top);
            checkDuplicateOwners(f.name(),group,perViewHeads,s,p,top);
            checkNativeHandover(f.name(),group);
        }
        require(absorbed>=2,"at least the two reproducing fixtures must drop an interior crossing, absorbed="+absorbed);
        require(cells>0,"the seam sweep has to cover native cells around the shared planes");
        require(seamViewSamples>0,"the seam sweep has to sample the running surface a view draws across a shared plane");
        checkInteriorCut();
        System.out.println("PASS: asymmetric four-way crossings keep every shared flangeway clear, the shared centre replaces the interior crossings it draws, and native rails hand over at the shared planes without a gap or a doubled cell");
    }
    private static List<Fixture> fixtures(){
        var out=new ArrayList<Fixture>();
        out.add(new Fixture("sym-straight",Regression.scissors(false),false));
        out.add(new Fixture("sym-curved",Regression.scissors(true),false));
        out.add(new Fixture("asym-reported",reported(),false));
        out.add(new Fixture("asym-curved-through",curvedThrough(),true));
        out.add(new Fixture("asym-reversed",reverse(curvedThrough()),true));
        out.add(new Fixture("asym-skew",skew(),true));
        return out;
    }
    /** Mirrors ReportedGeometryRegression: irregular corner nodes on cubic through roads. */
    private static List<Track> reported(){
        var roads=new ArrayList<Track>();String[] starts={"sw","se","sw","se"},ends={"nw","ne","ne","nw"};
        V3[] nodes={new V3(-3,0,-34),new V3(3,0,-20),new V3(-1,0,30),new V3(5,0,24)};
        int[] from={0,1,0,1},to={2,3,3,2};
        for(int r=0;r<4;r++){var points=new ArrayList<V3>();V3 first=nodes[from[r]],last=nodes[to[r]];
            for(int i=0;i<=320;i++){double u=i/320D,v=1-u;points.add(first.mul(v*v*v).add(first.add(0,0,14).mul(3*v*v*u)).add(last.add(0,0,-14).mul(3*u*u*v)).add(last.mul(u*u*u)));}
            roads.add(new Track("as"+r,starts[r],ends[r],points));}
        return roads;
    }
    /** Curved through roads, straight diagonals: the diagonals meet off the centre of the shared
     *  crossing, which is what adds the interior junction a symmetric layout never has. */
    private static List<Track> curvedThrough(){
        var out=new ArrayList<Track>();
        for(int r=0;r<2;r++){var points=new ArrayList<V3>();for(int i=0;i<=160;i++){double u=i/160D,x=(r==0?-3:3)+2.5*Math.sin(Math.PI*u);points.add(new V3(x,0,-20+40*u));}out.add(new Track("ct"+r,r==0?"sw":"se",r==0?"nw":"ne",points));}
        for(int r=0;r<2;r++){var points=new ArrayList<V3>();for(int i=0;i<=160;i++){double u=i/160D,x=(r==0?-3:3)+6*(r==0?u:-u);points.add(new V3(x+r*.4,0,-20+40*u));}out.add(new Track("cd"+r,r==0?"sw":"se",r==0?"ne":"nw",points));}
        return out;
    }
    /** Same shape with unequal diagonal lengths and a shifted central crossing. */
    private static List<Track> skew(){
        var roads=new ArrayList<Track>();
        roads.add(new Track("sk0","sw","nw",line(-3,-3,-24,36)));
        roads.add(new Track("sk1","se","ne",line(3,3,-18,42)));
        roads.add(new Track("sk2","sw","ne",line(-3,4,-24,36)));
        roads.add(new Track("sk3","se","nw",line(3,-2,-18,42)));
        return roads;
    }
    private static List<V3> line(double x0,double x1,double z0,double z1){
        var points=new ArrayList<V3>();for(int i=0;i<=200;i++){double u=i/200D;points.add(new V3(x0+(x1-x0)*u,0,z0+(z1-z0)*u));}return points;
    }
    /** Road order and point order both reversed: the layout must not depend on either. */
    private static List<Track> reverse(List<Track> roads){
        var out=new ArrayList<Track>();
        for(int i=roads.size()-1;i>=0;i--){var t=roads.get(i);var points=new ArrayList<>(t.points);Collections.reverse(points);out.add(new Track(t.id,t.endNode,t.startNode,points));}
        return out;
    }
    /** The interior crossing that used to be a view of its own still blocks the shared flangeways
     *  with its own mesh, and the shared cut is what removes exactly that steel. */
    private static void checkInteriorCut()throws Exception{
        Profile raw=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;Profile p=raw.tune(s);double top=p.top()+s.verticalOffset();
        var roads=curvedThrough();var junctions=Detector.find(roads);var group=ScissorsLayout.find(junctions).get(0);
        Junction interior=null;
        for(Junction j:junctions)if(!ScissorsLayout.absorbedByCentre(List.of(group),j)&&group.central(j.center())){interior=j;break;}
        require(interior!=null,"the reproducing fixture has to contain an interior crossing");
        Mesh own=PointMesh.build(interior,s,raw,0);
        int before=blockedCentral(roads,group,headTops(own,top),s,p);
        require(before>0,"an interior crossing drawn on its own leaves steel in the shared flangeways ("+before+" samples)");
        int after=blockedCentral(roads,group,headTops(group.cutShared(own,s,raw),top),s,p);
        require(after==0,"the shared cut has to clear every shared flangeway of that steel ("+after+" samples left)");
    }
    /** The seam where the shared centre hands over to a member turnout: a native cell that the mod
     *  leaves drawn must not be covered by modded steel, and a hidden cell must not be left open.
     *  A shared plane cuts through the native cell grid, so the decision is taken over the whole
     *  cell - the rule the renderer mixin passes both cell ends for. */
    private static int checkSeam(String name,ScissorsLayout group,List<PointClient.View> views,List<List<Mesh.Quad>> perView,List<Mesh.Quad> heads,PointSettings s,Profile p,double top){
        double cell=.5;int hiddenSeen=0,drawnSeen=0;
        // Only the through roads carry a continuous outer stock rail: a diverging road opens its
        // blade on purpose, so its own running line is not a seam the mod has to close.
        for(Track road:group.through())for(int sign:new int[]{-1,1})for(double plane:new double[]{group.intersection(road,group.lo()),group.intersection(road,group.hi())}){
            if(plane<=1||plane>=road.length-1)continue;
            for(double phase=0;phase<cell;phase+=.125)for(double c0=Math.floor((plane-1)/cell)*cell+phase;c0<plane+1;c0+=cell){
                double lo=Math.max(0,c0),hi=Math.min(road.length,c0+cell);if(hi-lo<cell-.01)continue;
                boolean hidden=PointClient.suppress(views,road.id,"default",road.at((lo+hi)/2),0);
                boolean covered=false,sampled=false;String part="";
                for(double d=lo;d<=hi;d+=.02){
                    V3 q=rail(road,d,sign,p);
                    // Where another road of the layout crosses this one, its flange channel removes
                    // the head on purpose: only the open running line is a seam that must be closed.
                    if(inSharedChannel(group,road,q,s,p))continue;
                    sampled=true;var hit=cover(heads,q);if(!hit.isEmpty()){
                        covered=true;part=hit.get(0).part();
                        for(var face:hit)require(Math.abs(headTop(face)-top)<=1e-3,name+" "+road.id+" sign="+sign+": the running head steps to "+round(headTop(face))+" against "+round(top)+" across the shared seam");
                        // Exactly one mesh may draw the running surface across the seam: a second
                        // view there is the doubled, flickering rail the report shows at this
                        // circle. Faces that only the pooled check steel supplies are not the
                        // running line of this road and are skipped.
                        int owners=0;
                        for(var list:perView){for(var face:list)if(Math.abs(headTop(face)-top)<=1e-3&&(triangle(q,face.a(),face.b(),face.c())||triangle(q,face.a(),face.c(),face.d()))){owners++;break;}}
                        if(owners>0){seamViewSamples++;require(owners==1,name+" "+road.id+" sign="+sign+": "+owners+" meshes draw the running surface across the shared seam at "+round(q));}
                        break;
                    }
                }
                if(!sampled)continue;
                if(hidden)hiddenSeen++;else drawnSeen++;
                require(hidden==covered,name+" "+road.id+" sign="+sign+": the native cell "+round(lo)+".."+round(hi)+" at the shared seam is "+(hidden?"hidden":"left drawn")+" while modded steel "+(covered?"covers":"misses")+" the same steel ("+part+")");
            }
        }
        require(drawnSeen==0,name+": a shared plane that the mod covers on both sides must hide every cell it touches");
        return hiddenSeen;
    }
    /** No second mesh may draw a layout road anywhere along it: a duplicate view doubles every rail
     *  it owns, which is what the report shows as an offset, flickering or broken outer rail. A
     *  shared centre plus the member turnouts must hand the running surface over exactly once. */
    private static void checkDuplicateOwners(String name,ScissorsLayout group,List<List<Mesh.Quad>> perViewHeads,PointSettings s,Profile p,double top){
        int doubled=0;StringBuilder first=new StringBuilder();
        for(Track road:group.tracks())for(int sign:new int[]{-1,1})for(double d=.05;d<road.length-.05;d+=.25){
            V3 q=rail(road,d,sign,p);int owners=0;
            for(var list:perViewHeads){for(var face:list)if(Math.abs(headTop(face)-top)<=1e-3&&(triangle(q,face.a(),face.b(),face.c())||triangle(q,face.a(),face.c(),face.d()))){owners++;break;}}
            if(owners>1){doubled++;if(first.length()<200)first.append(" [").append(road.id).append(" sign=").append(sign).append(" at ").append(round(q)).append(" owners=").append(owners).append("]");}
        }
        require(doubled==0,name+": "+doubled+" running-surface samples inside the layout are drawn by more than one mesh"+first);
    }
    /** Around a shared plane the two owners must cover the native rail without a hole: the member
     *  turnout outward, the shared centre inward. That contiguity is what allows the whole
     *  straddling native cell to be dropped. */
    private static void checkNativeHandover(String name,ScissorsLayout group){
        for(Junction y:group.turnouts())for(Track road:y.tracks()){
            double end=group.end(y,road);
            if(end<=.6||end>=road.length-.6)continue;
            require(group.owns(y,road.id,road.at(end-.35)),name+" "+road.id+": the member turnout owns the native rail outside its shared plane");
            require(group.owns(group.crossing(),road.id,road.at(end+.35)),name+" "+road.id+": the shared centre owns the native rail inside its shared plane");
        }
    }
    private static int blockedCentral(List<Track> roads,ScissorsLayout group,List<Mesh.Quad> heads,PointSettings s,Profile p){
        int blocked=0;
        for(Track road:roads)for(int sign:new int[]{-1,1})for(double d=.05;d<road.length-.05;d+=.05)for(double across:new double[]{-.015,0,.015}){
            V3 q=channelPoint(road,d,sign,across,s,p);
            if(group.central(q)&&!cover(heads,q).isEmpty())blocked++;
        }
        return blocked;
    }
    private static String describe(List<Track> roads,ScissorsLayout group,List<Mesh.Quad> heads,PointSettings s,Profile p){
        var text=new StringBuilder();
        for(Track road:roads)for(int sign:new int[]{-1,1})for(double d=.05;d<road.length-.05;d+=.05)for(double across:new double[]{-.015,0,.015}){
            V3 q=channelPoint(road,d,sign,across,s,p);
            var hit=cover(heads,q);
            if(group.central(q)&&!hit.isEmpty()&&text.length()<180)text.append(" [").append(road.id).append(" sign=").append(sign).append(" ").append(hit.get(0).part()).append(" at ").append(round(q)).append("]");
        }
        return text.toString();
    }
    private static V3 channelPoint(Track road,double d,int sign,double across,PointSettings s,Profile p){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        return road.at(d).add(road.tangent(d).lateral().mul(sign*(p.centerOffset()-p.headWidth()/2-gap/2)+across));
    }
    private static V3 rail(Track road,double d,int sign,Profile p){return road.at(d).add(road.tangent(d).lateral().mul(sign*p.centerOffset()));}
    private static boolean inSharedChannel(ScissorsLayout group,Track road,V3 q,PointSettings s,Profile p){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        for(Track other:group.tracks()){
            if(other==road)continue;
            double d=other.nearest(q);if(d<0||d>other.length)continue;
            for(int sign:new int[]{-1,1})if(q.distance(channelPoint(other,d,sign,0,s,p))<gap/2+1e-6)return true;
        }
        return false;
    }
    private static double headTop(Mesh.Quad q){double top=-Double.MAX_VALUE;for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))top=Math.max(top,v.y());return top;}
    /** Steel that reaches the running surface: head tops, cut walls and clipped faces alike. */
    private static List<Mesh.Quad> headTops(Mesh mesh,double height){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads)if(runningBand(q,height))result.add(q);
        return result;
    }
    private static List<Mesh.Quad> headTopsOf(List<Mesh.Quad> faces,double height){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:faces)if(runningBand(q,height))result.add(q);
        return result;
    }
    private static boolean runningBand(Mesh.Quad q,double height){
        boolean reach=false;
        for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){if(!Double.isFinite(v.x()+v.z()))continue;if(v.y()>=height-1e-3)reach=true;}
        return reach&&minHeight(q)>=5e-4;
    }
    private static double minHeight(Mesh.Quad q){
        double area=Math.abs(V3.crossXZ(q.b().sub(q.a()),q.c().sub(q.a())))+Math.abs(V3.crossXZ(q.c().sub(q.a()),q.d().sub(q.a())));
        double longest=0;V3[] v={q.a(),q.b(),q.c(),q.d()};
        for(int i=0;i<4;i++)longest=Math.max(longest,v[i].distance(v[(i+1)%4]));
        return longest<1e-9?0:area/longest;
    }
    private static List<Mesh.Quad> cover(List<Mesh.Quad> faces,V3 point){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))result.add(q);
        return result;
    }
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
    private static double round(double v){return Math.round(v*1000)/1000D;}
    private static String round(V3 v){return "("+round(v.x())+","+round(v.z())+")";}
}
