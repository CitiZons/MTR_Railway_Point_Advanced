package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Flange-way cutting for every planar crossing at one running height, not only the ones a scissors
 *  layout claims, plus the two-sided stock rail seam of a turnout. Every expectation is sampled on the
 *  world mesh the frame really draws: the pooled component assembly plus each view's filtered steel. */
public final class UniversalCrossingRegression {
    private static final List<String> failures=new ArrayList<>();
    private static PointSettings s;
    private static Profile raw,p;
    private static double top;
    private static int crossingSamples,seamCells;
    public static void run()throws Exception{
        s=PointSettings.DEFAULT;raw=Profile.STANDARD;p=raw.tune(s);top=p.top()+s.verticalOffset();
        for(Fixture f:fixtures())checkFixture(f);
        checkYSeam();
        checkSnappedWindow();
        checkDetailSection();
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        System.out.println("PASS: "+fixtures().size()+" planar crossing fixtures cut every flangeway at one running height on the world mesh ("
            +crossingSamples+" flangeway samples), leave grade separations and tangential passes intact, draw each rail through a crossing once, and hand the native rail"
            +" over on both sides of a turnout exactly on the cell grid the mod snaps to, with at most the declared allowance of straddling cells on other grids ("+seamCells+" seam cells)");
    }
    /** A resource pack replaces the swept section with the faces of its own rail model. The crossing
     *  still has to cut its flange ways through that section, not only through the generic beams. */
    private static void checkDetailSection(){
        double head=.1,base=p.top()-p.railHeight(),bottom=-.165;
        var faces=new ArrayList<Mesh.Quad>();
        faces.add(box(-head/2,0,head/2,0));          // running surface
        faces.add(side(-head/2,0,bottom));           // left cheek
        faces.add(side(head/2,0,bottom));            // right cheek
        faces.add(box(-head/2,bottom,head/2,bottom));// foot
        var detail=new ModelDetail(faces,List.of(),List.of(),0,0,head,-head/2,head/2,.075,bottom,false);
        var profile=new Profile(p.gauge(),p.top(),p.headWidth(),p.footWidth(),p.railHeight(),p.steel(),p.sleeper(),"test-detail",false,detail);
        var tracks=plain(90);
        var junctions=Detector.find(tracks);
        check(junctions.size()==1,"a custom base section still has to find the crossing");
        if(junctions.isEmpty())return;
        var j=junctions.get(0);
        Mesh mesh=PointMesh.build(j,s,profile,0);
        var heads=headTops(mesh,p.top());
        check(!heads.isEmpty(),"a custom base section has to reach the running surface ("+heads.size()+" faces)");
        int blocked=0,sampled=0;StringBuilder where=new StringBuilder();
        for(Track r:List.of(j.a(),j.b())){
            double d0=r.nearest(j.center());
            for(int sign:new int[]{-1,1})for(double off=-.6;off<=.6001;off+=.02){
                double d=d0+off;if(d<.05||d>r.length-.05)continue;
                V3 q=channelPoint(r,d,sign,s,profile);sampled++;
                var hit=cover(heads,q);
                if(!hit.isEmpty()){blocked++;if(where.length()<160)where.append(" [").append(r.id).append(" sign=").append(sign).append(" ").append(hit.get(0).part()).append(" at ").append(round(q)).append("]");}
            }
        }
        check(sampled>0,"the custom base section sweep has to sample both flangeways");
        check(blocked==0,"custom base section: "+blocked+" of "+sampled+" flangeway samples across the crossing carry steel"+where);
        System.out.println("PASS: crossing "+j.id()+" with a resource-pack rail section keeps "+sampled+" flangeway samples clear on the model's own faces");
    }
    private static Mesh.Quad box(double x1,double y1,double x2,double y2){
        return new Mesh.Quad(new V3(x1,y1,0),new V3(x2,y2,0),new V3(x2,y2,1),new V3(x1,y1,1),p.steel(),"rail",0,null);
    }
    private static Mesh.Quad side(double x,double yTop,double yBottom){
        return new Mesh.Quad(new V3(x,yTop,0),new V3(x,yBottom,0),new V3(x,yBottom,1),new V3(x,yTop,1),p.steel(),"rail",0,null);
    }
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}
    private record Fixture(String name,List<Track> tracks,List<V3> crossings,boolean cut,int nativeCellAllowance){
        Fixture(String name,List<Track> tracks,List<V3> crossings,boolean cut){this(name,tracks,crossings,cut,0);}
    }
    private static List<Fixture> fixtures(){
        var out=new ArrayList<Fixture>();
        out.add(new Fixture("plain-90",plain(90),List.of(new V3(0,0,0)),true));
        out.add(new Fixture("plain-skew",plain(30),List.of(new V3(0,0,0)),true));
        out.add(new Fixture("plain-reversed",reverse(plain(52)),List.of(new V3(0,0,0)),true));
        out.add(new Fixture("curved-multi",curvedMulti(),List.of(new V3(0,0,0),new V3(12,0,0),new V3(-12,0,0)),true));
        out.add(new Fixture("five-centre",fiveCentre(),List.of(new V3(0,0,0)),true));
        out.add(new Fixture("short-rail",shortRail(),List.of(new V3(6,0,0)),true));
        out.add(new Fixture("short-rail-reversed",reverse(shortRail()),List.of(new V3(6,0,0)),true));
        out.add(new Fixture("sym-scissors",Regression.scissors(false),List.of(),true));
        // The only fixture with an allowance: an asymmetric crossover whose crossing heart is drawn
        // next to a turnout. The heart steel is emitted on its own sampling, so at a hand-over edge it
        // can cross one 0.5 m native cell that the window no longer hides. Reported, not hidden: the
        // measured count is printed and every required fixture keeps a zero allowance.
        // Offline there is no Rail to snap to, so RailSampler.yBoundary falls back to the nominal
        // window and its end lands inside a native cell: that cell's centre stays outside (native rail
        // kept) while the modded mesh, which stops on the nominal end, still covers the inner half of
        // it - the "native and modded" pairs below. In game lastCell snaps the window end onto a repeat
        // cell edge, so each such cell is either wholly hidden and drawn, or wholly native. The
        // allowance stays until the offline fixture can snap a synthetic cell grid the same way.
        out.add(new Fixture("asym-scissors",asymScissors(),List.of(),true,2));
        out.add(new Fixture("sym-scissors-curved",Regression.scissors(true),List.of(),true));
        out.add(new Fixture("overpass",overpass(),List.of(new V3(0,0,0)),false));
        out.add(new Fixture("near-tangent",nearTangent(),List.of(new V3(0,0,0)),false));
        return out;
    }
    private static void checkFixture(Fixture f)throws Exception{
        var junctions=Detector.find(f.tracks());
        var diamonds=junctions.stream().filter(j->j.kind()==Junction.Kind.DIAMOND).toList();
        if(!f.cut()){
            // Grade separation or a tangential pass: nothing may be claimed, so nothing gets cut.
            check(diamonds.isEmpty(),f.name()+": a track that passes over or only touches must not become a crossing junction");
            return;
        }
        for(V3 c:f.crossings())check(diamonds.stream().anyMatch(j->j.center().distance(c)<.6),
            f.name()+": the crossing at "+round(c)+" has to be claimed as a diamond junction (found "+diamonds.size()+": "+centres(diamonds)+")");
        var views=PointRenderer.viewsForTest(junctions,s,raw);
        var world=PointRenderer.worldForTest(views);
        var heads=headTops(world,top);
        var perView=new ArrayList<List<Mesh.Quad>>();
        for(var v:views)perView.add(headTops(v.mesh(),top));
        var detail=new StringBuilder();
        for(var v:views){
            detail.append(" [").append(v.junction.id()).append(":").append(v.mesh().quads.size()).append(v.centreHidden?" hidden":"").append(" roads=").append(v.centreRoads==null?0:v.centreRoads.size()).append(" ch=").append(v.channels.size());
            if(v.centreRoads!=null&&v.diamond!=null)for(int i=0;i<v.centreRoads.size();i++){var w=v.diamond.window(i);detail.append(" ").append(v.centreRoads.get(i).id).append("=").append(round(w[0])).append("..").append(round(w[1]));}
            detail.append("]");
        }
        System.out.println("     "+f.name()+": "+junctions.size()+" junctions, "+diamonds.size()+" crossings, "+views.size()+" views, "+world.quads.size()+" world faces"+detail);
        check(heads.size()>100,f.name()+": the world mesh has to carry the running steel ("+heads.size()+" running faces, "+views.size()+" views)");
        var crossings=new ArrayList<V3>(f.crossings());
        for(var j:diamonds)if(crossings.stream().noneMatch(c->c.distance(j.center())<.6))crossings.add(j.center());
        int blocked=0,sampled=0;StringBuilder where=new StringBuilder();
        for(V3 c:crossings){
            var parts=participants(f.tracks(),c);
            check(parts.size()>=2,f.name()+": a crossing at "+round(c)+" needs two physical tracks, got "+parts.size());
            for(Track r:parts){
                double d0=r.nearest(c);
                for(int sign:new int[]{-1,1})for(double off=-.6;off<=.6001;off+=.02){
                    double d=d0+off;if(d<.05||d>r.length-.05)continue;
                    V3 q=channelPoint(r,d,sign,s,p);sampled++;
                    var hit=cover(heads,q);
                    if(!hit.isEmpty()){blocked++;if(where.length()<220)where.append(" [").append(r.id).append(" sign=").append(sign).append(" ").append(hit.get(0).part()).append(" at ").append(round(q)).append(owner(views,q)).append("]");}
                }
            }
        }
        crossingSamples+=sampled;
        check(sampled>0,f.name()+": the crossing sweep has to sample both flangeways of every crossing");
        check(blocked==0,f.name()+": "+blocked+" of "+sampled+" flangeway samples across the crossings still carry steel"+where);
        duplicateOwners(f.name(),junctions,views.stream().map(v->v.junction.id()).toList(),perView);
        seamCells+=nativeCells(f.name(),f.tracks(),junctions,views,heads,f.nativeCellAllowance());
    }
    private static String centres(List<Junction> junctions){
        var text=new StringBuilder();
        for(var j:junctions)if(text.length()<160)text.append(round(j.center()));
        return text.toString();
    }
    /** Only one mesh may draw a road inside its own junction window: a second owner is the doubled,
     *  flickering rail a common centre or an absorbed interior crossing produces. */
    private static void duplicateOwners(String name,List<Junction> junctions,List<String> ids,List<List<Mesh.Quad>> perView){
        int doubled=0,sampled=0;StringBuilder first=new StringBuilder();
        for(Junction j:junctions)for(Track r:j.tracks()){
            double limit=PointMesh.extent(j,s),c=r.nearest(j.center());
            for(double d=Math.max(0,c-limit);d<=Math.min(r.length,c+limit);d+=.25)for(int sign:new int[]{-1,1}){
                if(!j.contains(r.id,r.at(d),0,limit))continue;
                V3 q=rail(r,d,sign,p);sampled++;int owners=0;var who=new StringBuilder();
                for(int i=0;i<perView.size();i++){for(var face:perView.get(i))if(triangle(q,face.a(),face.b(),face.c())||triangle(q,face.a(),face.c(),face.d())){owners++;who.append(" ").append(ids.get(i));break;}}
                if(owners>1){doubled++;if(first.length()<220)first.append(" [").append(r.id).append(" at ").append(round(q)).append(" d=").append((int)(d*100)/100D).append(" owners=").append(owners).append(who).append("]");}
            }
        }
        check(sampled>0,name+": the owner sweep has to sample the junction windows");
        check(doubled==0,name+": "+doubled+" running-surface samples are drawn by more than one mesh"+first);
    }
    /** The native rail hands over to the modded mesh at the junction window on both sides. Whatever
     *  cell grid MTR uses, a cell the mod hides has to be a cell the mod covers with its own steel. */
    private static int nativeCells(String name,List<Track> tracks,List<Junction> junctions,List<PointClient.View> views,List<Mesh.Quad> heads,int allowance){
        int seen=0,gaps=0;StringBuilder first=new StringBuilder();
        for(Junction j:junctions)for(Track r:j.tracks()){
            double c=r.nearest(j.center()),limit=PointMesh.extent(j,s);
            // A turnout only carries a running stock rail on the outer side of each road; the inner
            // line is the moving blade and is covered by the closure rail instead.
            var signs=j.kind()==Junction.Kind.Y?new int[]{outerSign(j,r)}:new int[]{-1,1};
            double anchor=windowEnd(views,r.id);
            for(int sign:signs)for(int pass=0;pass<5;pass++){
                // Four arbitrary phases stress the seam against whatever cell grid MTR uses; the fifth
                // is the phase the mod itself snaps to in game, where lastCell puts the window end on a
                // cell edge. That phase has to agree exactly: it is the hand-over the player sees.
                if(pass==4&&anchor<0)continue;
                double phase=pass<4?pass*.125:anchor-Math.floor(anchor/.5)*.5;
                int strict=0;
                for(double c0=Math.floor((c-limit-1.5)/.5)*.5+phase;c0<c+limit+1.5;c0+=.5){
                    double lo=Math.max(0,c0),hi=Math.min(r.length,c0+.5);if(hi-lo<.49)continue;
                    V3 mid=rail(r,(lo+hi)/2,sign,p);
                    if(inChannel(tracks,r,mid))continue;
                    boolean hidden=PointClient.suppress(views,r.id,"default",mid,0);
                    boolean covered=!cover(heads,mid).isEmpty();
                    seen++;
                    if(hidden==covered)continue;
                    gaps++;strict++;
                    if(first.length()<300)first.append(" [").append(r.id).append(" sign=").append(sign).append(" cell ").append(round(lo)).append("..").append(round(hi))
                        .append(hidden?" hidden but open at ":" native and modded at ").append(round(mid)).append(owner(views,mid)).append("]");
                }
                if(pass==4){
                    // The snapped hand-over is the one the player sees, so it is held to the declared
                    // allowance of this fixture rather than to the stress-phase tolerance: the count
                    // printed here is a real seam defect to drive to zero, not grid noise.
                    if(strict>0)System.out.println("     "+name+": "+strict+" native cells disagree even with the grid aligned to the window end (allowance "+allowance+")");
                    check(strict==0,name+": with the cell grid aligned to the window end (the phase the mod snaps to in game) "+strict
                        +" of the sampled native cells still disagree, so the snapped hand-over overlaps or opens");
                }
            }
        }
        check(seen>0,name+": the seam sweep has to sample native cells");
        if(gaps>0)System.out.println("     "+name+": "+gaps+" of "+seen+" native cells straddle the hand-over edge (declared allowance "+allowance+")"+first);
        check(gaps<=allowance,name+": the native cell grid must agree with the modded mesh at the junction window ("+gaps+" of "+seen+" sampled cells disagree, allowance "+allowance+")"+first);
        return seen;
    }
    /** The end of the window the views claim on this rail, exactly as the renderer computes it. */
    private static double windowEnd(List<PointClient.View> views,String railId){
        double end=-1;
        for(var v:views){
            var j=v.junction;var b=v.boundary;
            if(b==null&&v.scissors!=null&&j.kind()!=Junction.Kind.DIAMOND)b=v.scissors.boundary(j,v.settings);
            if(b!=null){
                if(railId.equals(j.a().id))end=Math.max(end,b.aEnd());
                else if(railId.equals(j.b().id))end=Math.max(end,b.bEnd());
                else if(j.third()!=null&&railId.equals(j.third().id))end=Math.max(end,b.thirdEnd());
            }
            if(v.diamond!=null){
                var roads=v.centreRoads!=null?v.centreRoads:j.tracks();
                for(int i=0;i<roads.size();i++)if(roads.get(i).id.equals(railId)){
                    double[] w=v.diamond.window(i);end=Math.max(end,Math.max(w[0],w[1]));
                }
            }
        }
        return end;
    }
    /** Which view draws this point, for the seam diagnostics. */
    private static String owner(List<PointClient.View> views,V3 q){
        var text=new StringBuilder();
        for(var v:views){var hit=cover(headTops(v.mesh(),top),q);if(!hit.isEmpty())text.append(" drawn by ").append(v.junction.id()).append("/").append(hit.get(0).part());}
        return text.toString();
    }
    /** The whole turnout: both stock rails and both blade sides stay covered wherever the mod hides
     *  the native rail, and neither side may keep a hidden stretch the modded mesh does not draw. */
    private static void checkYSeam(){
        var tracks=Regression.y();
        var junctions=Detector.find(tracks);
        check(!junctions.isEmpty(),"the turnout fixture has to be detected");
        var views=PointRenderer.viewsForTest(junctions,s,raw);
        Mesh world=PointRenderer.worldForTest(views);
        var heads=headTops(world,top);
        for(Junction j:junctions){
            double limit=PointMesh.extent(j,s);
            for(Track r:j.tracks()){
                // Both stock rails are the running lines of a turnout: the outer side of each road,
                // the side that faces away from the other road. The blade side is the moving steel
                // and is covered by the closure rail, so it is not a stock-rail seam.
                int sign=outerSign(j,r);
                double c=r.nearest(j.center());
                int covered=0,open=0;StringBuilder first=new StringBuilder();
                for(double d=Math.max(.05,c-limit);d<=Math.min(r.length,c+limit);d+=.05){
                    V3 q=rail(r,d,sign,p);
                    if(inChannel(tracks,r,q))continue;
                    boolean hidden=PointClient.suppress(views,r.id,"default",q,0);
                    boolean hit=!cover(heads,q).isEmpty();
                    if(hit)covered++;else open++;
                    check(!(hidden&&!hit),j.id()+" "+r.id+" sign="+sign+" at "+round(q)+": the native rail is hidden but the modded mesh misses the running surface");
                }
                check(covered+open>10,j.id()+" "+r.id+" sign="+sign+": the rail line sweep has to sample the junction");
            }
        }
    }
    /** The stock rail side of a road: the lateral sign whose running line faces away from the other road. */
    private static int outerSign(Junction j,Track r){
        double d=Math.min(r.length*.5,Math.max(.5,PointMesh.extent(j,s)*.5));
        if(j.kind()==Junction.Kind.DIAMOND)d=Math.max(0,r.nearest(j.center()));
        int best=1;double bestDistance=-1;
        for(int sign:new int[]{-1,1}){
            V3 q=rail(r,d,sign,p);double other=Double.MAX_VALUE;
            for(Track t:j.tracks())if(!t.id.equals(r.id)){double u=t.nearest(q);other=Math.min(other,t.at(u).distance(q));}
            if(other>bestDistance){bestDistance=other;best=sign;}
        }
        return best;
    }
    /** The window has to follow the native cells, so a cell the mod hides is a cell it also draws. */
    private static void checkSnappedWindow(){
        List<double[]> cells=List.of(new double[]{0,.5},new double[]{.5,1},new double[]{1,1.5},new double[]{1.5,2},new double[]{2,2.5},new double[]{2.5,3});
        double[] window=RailSampler.window(cells,1.2,2.3);
        check(window[0]==1&&window[1]==2.5,"the snapped window has to cover every cell whose centre is inside: "+Arrays.toString(window));
        window=RailSampler.window(cells,0,2.2);
        check(window[0]==0&&window[1]==2,"a window starting at a cell edge must not open: "+Arrays.toString(window));
        window=RailSampler.window(cells,2.6,3.4);
        check(window[0]==2.5&&window[1]==3,"a window inside the last cell must still cover it: "+Arrays.toString(window));
    }
    // ---- fixtures
    private static List<Track> plain(double degrees){
        double t=Math.toRadians(degrees),dx=Math.cos(t)*24,dz=Math.sin(t)*24;
        return List.of(Regression.line("a","aw","ae",new V3(-24,0,0),new V3(24,0,0)),
            Regression.line("b","bn","bs",new V3(-dx,0,-dz),new V3(dx,0,dz)));
    }
    private static List<Track> curvedMulti(){
        var points=new ArrayList<V3>();
        for(int i=0;i<=240;i++){double x=-24+i*.2;points.add(new V3(x,0,3*Math.sin(Math.PI*x/12)));}
        return List.of(new Track("a","aw","ae",points),
            Regression.line("b","bw","be",new V3(-26,0,0),new V3(26,0,0)));
    }
    private static List<Track> fiveCentre(){
        var out=new ArrayList<Track>();
        for(int i=0;i<5;i++){double t=Math.toRadians(36*i);out.add(Regression.line("c"+i,"n"+i,"s"+i,new V3(-Math.sin(t)*26,0,-Math.cos(t)*26),new V3(Math.sin(t)*26,0,Math.cos(t)*26)));}
        return out;
    }
    /** An asymmetric crossover: unequal corner nodes, skewed through roads, and the extra interior
     *  intersection the two crossovers leave between them. */
    private static List<Track> asymScissors(){
        return List.of(
            Regression.line("a1","aw","A1",new V3(-26,0,-2),new V3(-4,0,.538)),
            Regression.line("a2","A1","A2",new V3(-4,0,.538),new V3(5,0,1.577)),
            Regression.line("a3","A2","ae",new V3(5,0,1.577),new V3(26,0,4)),
            Regression.line("b1","bw","B1",new V3(-.615,0,-26),new V3(-.083,0,-6)),
            Regression.line("b2","B1","B2",new V3(-.083,0,-6),new V3(.263,0,7)),
            Regression.line("b3","B2","be",new V3(.263,0,7),new V3(.769,0,26)),
            Regression.line("c1","A1","B1",new V3(-4,0,.538),new V3(-.083,0,-6)),
            Regression.line("c2","A2","B2",new V3(5,0,1.577),new V3(.263,0,7)));
    }
    private static List<Track> shortRail(){        return List.of(Regression.line("a1","aw","an",new V3(-24,0,0),new V3(6,0,0)),
            Regression.line("a2","an","ae",new V3(6,0,0),new V3(30,0,0)),
            Regression.line("b","bn","bs",new V3(6,0,-24),new V3(6,0,24)));
    }
    private static List<Track> overpass(){
        return List.of(Regression.line("a","aw","ae",new V3(-24,0,0),new V3(24,0,0)),
            Regression.line("b","bn","bs",new V3(0,3,-24),new V3(0,3,24)));
    }
    private static List<Track> nearTangent(){
        double t=Math.toRadians(.4),dx=Math.cos(t)*26,dz=Math.sin(t)*26;
        return List.of(Regression.line("a","aw","ae",new V3(-24,0,0),new V3(24,0,0)),
            Regression.line("b","bn","bs",new V3(-dx,0,-dz),new V3(dx,0,dz)));
    }
    private static List<Track> reverse(List<Track> tracks){
        var out=new ArrayList<Track>();
        for(int i=tracks.size()-1;i>=0;i--){var t=tracks.get(i);var points=new ArrayList<>(t.points);Collections.reverse(points);out.add(new Track(t.id+"r",t.endNode,t.startNode,points));}
        return out;
    }
    private static List<Track> participants(List<Track> tracks,V3 c){
        var out=new ArrayList<Track>();
        for(Track t:tracks){double d=t.nearest(c);if(d>=0&&d<=t.length&&t.at(d).distance(c)<.35)out.add(t);}
        return out;
    }
    private static boolean inChannel(List<Track> tracks,Track road,V3 q){
        double gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
        for(Track other:tracks){
            if(other.id.equals(road.id))continue;
            double d=other.nearest(q);if(d<0||d>other.length)continue;if(other.at(d).distance(q)>1.4)continue;
            for(int sign:new int[]{-1,1})if(q.distance(channelPoint(other,d,sign,s,p))<gap/2+1e-6)return true;
        }
        return false;
    }
    // ---- sampling helpers
    private static V3 rail(Track road,double d,int sign,Profile profile){return road.at(d).add(road.tangent(d).lateral().mul(sign*profile.centerOffset()));}
    private static V3 channelPoint(Track road,double d,int sign,PointSettings settings,Profile profile){
        double gap=Math.max(.02,settings.flangeway()+settings.wingGapDelta());
        return road.at(d).add(road.tangent(d).lateral().mul(sign*(profile.centerOffset()-profile.headWidth()/2-gap/2)));
    }
    private static List<Mesh.Quad> headTops(Mesh mesh,double height){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads)if(runningBand(q,height))result.add(q);
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
