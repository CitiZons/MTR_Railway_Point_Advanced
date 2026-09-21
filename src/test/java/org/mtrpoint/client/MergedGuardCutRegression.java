package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.*;
import java.util.*;

/**
 * Regression for the merged (multi-source, cross-junction) guard run on a shallow crossing.
 *
 * <p>A 20 degree crossing next to a turnout that shares one of its roads: the crossing's check rail
 * on that road and the turnout's own check rail are the same physical rail, so the pooled bake
 * merges them into one run with two sources. The measured risk is that the merge, the exposed ends
 * or the run's own extent changes which crossing rails its spans are cut against, and the merged
 * steel then runs straight through the rail that crosses it.
 *
 * <p>The oracle is the pre-pooling path: the same fixture baked the legacy way draws exactly the
 * same physical check rail, so in a window around the point where the crossing rail's head band
 * covers that check rail's head footprint the two bakes must keep the same amount of steel. A
 * skipped cut on the merged path shows up as extra retained steel.
 */
public final class MergedGuardCutRegression {
    private static final double TOP=Profile.STANDARD.top();
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}

    public static void run()throws Exception{
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        for(double crossingAt:new double[]{4,6}){
            Track a1=Regression.line("mg-a1","m1a","m0",new V3(0,0,-30),new V3(0,0,0));
            Track a2=Regression.line("mg-a2","m0","m2",new V3(0,0,0),new V3(0,0,40));
            double angle=Math.toRadians(20);
            var branch=new ArrayList<V3>();
            for(int i=0;i<=100;i++){double t=i*.25;branch.add(new V3(Math.sin(angle)*t,0,Math.cos(angle)*t));}
            Track branchTrack=new Track("mg-branch","m0","mb",branch);
            V3 direction=new V3(Math.sin(angle),0,Math.cos(angle));
            Track crossing=Regression.line("mg-b","c0","c1",new V3(0,0,crossingAt).sub(direction.mul(30)),new V3(0,0,crossingAt).add(direction.mul(30)));
            var junctions=Detector.find(List.of(a1,a2,branchTrack,crossing));
            Junction diamond=junctions.stream().filter(j->j.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();
            Junction turnout=junctions.stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();

            var request=new DiamondGeometry.Request(diamond,s,p.tune(s),PointMesh.extent(diamond,s));
            var crossingRuns=GuardRails.crossing(diamond,s,p.tune(s));
            var turnoutRuns=GuardRails.forJunction(turnout,s,p);
            var pooledRuns=new ArrayList<GuardRails.Run>(crossingRuns);pooledRuns.addAll(turnoutRuns);
            var owners=new ArrayList<Set<Integer>>();
            var merged=GuardRails.exposeEnds(GuardRails.merge(pooledRuns,owners));
            int multiSource=0;
            for(var source:owners)if(source.size()>1)multiSource++;
            require(multiSource>0,"The fixture never merges a check rail across two junctions");
            require(merged.size()<pooledRuns.size(),"The fixture merge kept every run separate");

            Mesh legacy=DiamondGeometry.combine(List.of(request));
            Mesh pooled=DiamondGeometry.combine(List.of(request),pooledRuns);
            var views=List.of(
                PointRenderer.view(diamond,s,p,PointMesh.build(diamond,s,p,0,RailSampler.yBoundary(diamond,s)),null),
                PointRenderer.view(turnout,s,p,PointMesh.build(turnout,s,p,0,RailSampler.yBoundary(turnout,s)),null));
            Mesh assembled=PointRenderer.assembleForTest(views);
            var legacyTops=tops(legacy);
            var pooledTops=tops(pooled);
            var assembledTops=tops(assembled);

            int samples=0,legacyRetained=0,pooledRetained=0,assembledRetained=0;
            for(int index=0;index<merged.size();index++){
                GuardRails.Run run=merged.get(index);
                for(Track cutter:diamond.tracks()){
                    if(cutter.id.equals(run.road().id))continue;
                    for(int side:new int[]{-1,1}){
                        V3 centre=crossingPoint(run,cutter,side*p.centerOffset(),p);
                        if(centre==null)continue;
                        V3 along=horizontal(run.road().tangent(run.road().nearest(centre)));
                        V3 across=along.lateral();
                        // A window a quarter of a metre along the check rail and 15 cm across it,
                        // sampled only where the check rail's own head footprint overlaps the
                        // crossing rail's head band.
                        for(int i=-25;i<=25;i++)for(int k=-25;k<=25;k++){
                            V3 point=centre.add(along.mul(i*.01)).add(across.mul(k*.006));
                            double at=run.road().nearest(point);
                            double alpha=point.sub(run.road().at(at)).dot(horizontal(run.road().tangent(at)).lateral())-run.offset();
                            if(Math.abs(alpha)>p.headWidth()/2-.008)continue;
                            double e=cutter.nearest(point);
                            double beta=point.sub(cutter.at(e).add(cutter.tangent(e).lateral().mul(side*p.centerOffset()))).dot(horizontal(cutter.tangent(e)).lateral());
                            if(Math.abs(beta)>p.headWidth()/2-.008)continue;
                            samples++;
                            if(coverage(legacyTops,point)>0)legacyRetained++;
                            if(coverage(pooledTops,point)>0)pooledRetained++;
                            if(coverage(assembledTops,point)>0)assembledRetained++;
                        }
                    }
                }
            }
            require(samples>200,"Merged guard fixture too thin to prove anything: "+samples+" overlapping samples");
            require(legacyRetained>0,"The legacy bake was expected to keep the check rail's outer half");
            require(pooledRetained<=legacyRetained,
                "The pooled bake of the merged check run kept more steel than the legacy bake where the crossing rail covers it: "+pooledRetained+" vs "+legacyRetained+" of "+samples);
            require(assembledRetained<=legacyRetained,
                "The final assembly of the merged check run kept more steel than the legacy bake where the crossing rail covers it: "+assembledRetained+" vs "+legacyRetained+" of "+samples);
            // SurfaceUnion may only take coplanar overlap away, never add steel back.
            require(assembledRetained<=pooledRetained,"The final assembly duplicated merged check steel after banking: "+assembledRetained+" vs "+pooledRetained);
            require(assembledRetained>0,"The final assembly dropped the merged check steel entirely");
        }
    }

    /**
     * The same cross-junction situation, but the adjoining turnout is a movable-frog one with an
     * editor height override. It contributes no fixed-crossing request of its own style, so its
     * check rail joins no style group of the crossing: combine() hands the run to guards(), which
     * bakes only the run itself. guards() owns no crossing rail, so before the fix nothing could
     * ever cut that guard however much the two sections overlap.
     *
     * <p>The oracle is guards() itself: with no crossing span to cut against, the pooled bake used
     * to reproduce that uncut reference exactly, so any retained steel here is a skipped cut. The
     * clear-over arm is the opposite gate: a guard a whole section higher has no section left
     * inside the crossing rail and must keep exactly that steel.
     */
    public static void runOffStyleGuard()throws Exception{
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        double crossingAt=6;
        Track a1=Regression.line("og-a1","m1a","m0",new V3(0,0,-30),new V3(0,0,0));
        Track a2=Regression.line("og-a2","m0","m2",new V3(0,0,0),new V3(0,0,40));
        double angle=Math.toRadians(20);
        var branch=new ArrayList<V3>();
        for(int i=0;i<=100;i++){double t=i*.25;branch.add(new V3(Math.sin(angle)*t,0,Math.cos(angle)*t));}
        Track branchTrack=new Track("og-branch","m0","mb",branch);
        V3 direction=new V3(Math.sin(angle),0,Math.cos(angle));
        Track crossing=Regression.line("og-b","c0","c1",new V3(0,0,crossingAt).sub(direction.mul(30)),new V3(0,0,crossingAt).add(direction.mul(30)));
        var junctions=Detector.find(List.of(a1,a2,branchTrack,crossing));
        Junction diamond=junctions.stream().filter(j->j.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();
        Junction turnout=junctions.stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();

        var request=new DiamondGeometry.Request(diamond,s,p.tune(s),PointMesh.extent(diamond,s));
        // Same plan geometry, 75 mm higher: the two sections still interpenetrate (the detector
        // tolerates 80 mm), but the style key now matches no crossing request. The guard is
        // lengthened through the same editor control so it really reaches the crossing rails.
        PointSettings raised=s.with(11,.075).with(18,5).flags(true,true);
        double raisedTop=p.top()+.075;
        var raisedRuns=GuardRails.forJunction(turnout,raised,p);
        require(!raisedRuns.isEmpty(),"The off-style guard fixture produced no check runs");
        require(crossesACrossingRail(raisedRuns,diamond,p),"The off-style guard fixture has no guard across a crossing rail");
        double uncut=topArea(DiamondGeometry.guards(raisedRuns),raisedTop);
        double pooled=topArea(DiamondGeometry.combine(List.of(request),raisedRuns),raisedTop);
        require(uncut>1e-6,"The off-style guard fixture has no guard steel to cut: "+uncut+" m2");
        require(pooled<uncut-1e-6,"A pooled guard whose height matches no crossing style group is not cut by the crossing rail it crosses: "+pooled+" vs "+uncut+" m2 of retained guard steel");
        // A guard a whole section higher is clear of the crossing rail: it must keep its steel.
        PointSettings clear=s.with(11,.4).with(18,5).flags(true,true);
        double clearTop=p.top()+.4;
        var clearRuns=GuardRails.forJunction(turnout,clear,p);
        double clearUncut=topArea(DiamondGeometry.guards(clearRuns),clearTop);
        double clearPooled=topArea(DiamondGeometry.combine(List.of(request),clearRuns),clearTop);
        require(clearUncut>1e-6,"The clear-over guard fixture has no guard steel: "+clearUncut+" m2");
        require(Math.abs(clearPooled-clearUncut)<1e-9,"A guard running clear above the crossing lost steel: "+clearPooled+" vs "+clearUncut+" m2");
        // And the steel has to be taken exactly where the crossing rail covers the guard, not
        // merely shaved somewhere: inside the crossing rail's head band the uncut bake keeps steel
        // the pooled bake drops, while steel the crossing rail does not reach survives.
        var uncutTops=topsAt(DiamondGeometry.guards(raisedRuns),raisedTop);
        var pooledTops=topsAt(DiamondGeometry.combine(List.of(request),raisedRuns),raisedTop);
        int[] raisedCut=sample(raisedRuns,diamond,p,raisedTop,uncutTops,pooledTops);
        require(raisedCut[0]>0,"The pooled bake skipped the crossing cut over the off-style guard entirely");
        require(raisedCut[1]>0,"The pooled bake removed the whole off-style guard, not just the crossing footprint");
        var clearTops=topsAt(DiamondGeometry.guards(clearRuns),clearTop);
        var clearPooledTops=topsAt(DiamondGeometry.combine(List.of(request),clearRuns),clearTop);
        int[] clearCut=sample(clearRuns,diamond,p,clearTop,clearTops,clearPooledTops);
        require(clearCut[0]+clearCut[1]>0,"The clear-over guard fixture has no overlapping sample to prove anything");
        require(clearCut[0]==0,"A guard running clear above the crossing was cut inside the crossing rail's head band");
    }

    /**
     * The same off-style guard, but reached through the pooled path the world actually takes.
     * PointRenderer.assemble() asks DiamondGeometry.fixedY() for every movableFrog=false Y, and
     * fixedY() refuses only a movable frog, so a plain Y neighbour with an editor height override
     * contributes a crossing request of its own style. Its check run then matches that style group
     * (DiamondGeometry.combine(), the pool loop that removes matching runs from {@code remaining})
     * instead of falling through to guards(), and the group bake only ever saw its own rails: the
     * crossing rail of the neighbouring diamond could not cut it at all. runOffStyleGuard() cannot
     * see this, because it passes the crossing request alone and therefore always takes the
     * fallback.
     *
     * <p>The oracle is the fallback bake of the very same guard: combine() cuts it against the
     * crossing rails regardless of style, so that path already drew the intended steel. The
     * negative control is the same component with the crossing view removed: nothing may cut the
     * guard there, so every sample its own group's bake covers must survive. That own-group bake is
     * also the pre-fix pooled result, which kept every one of these samples. The probe is
     * volumetric on purpose: a 2D area probe over the guard's top faces is masked near the junction
     * by the neighbour's own check wings, which are pooled into the same band, so each sample asks
     * for real guard steel in a short vertical column at the guard's head level.
     */
    public static void runOffStyleFixedY()throws Exception{
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        double crossingAt=6;
        Track a1=Regression.line("fy-a1","f1a","f0",new V3(0,0,-30),new V3(0,0,0));
        Track a2=Regression.line("fy-a2","f0","f2",new V3(0,0,0),new V3(0,0,40));
        double angle=Math.toRadians(20);
        var branch=new ArrayList<V3>();
        for(int i=0;i<=100;i++){double t=i*.25;branch.add(new V3(Math.sin(angle)*t,0,Math.cos(angle)*t));}
        Track branchTrack=new Track("fy-branch","f0","fb",branch);
        V3 direction=new V3(Math.sin(angle),0,Math.cos(angle));
        Track crossing=Regression.line("fy-b","c0","c1",new V3(0,0,crossingAt).sub(direction.mul(30)),new V3(0,0,crossingAt).add(direction.mul(30)));
        var junctions=Detector.find(List.of(a1,a2,branchTrack,crossing));
        Junction diamond=junctions.stream().filter(j->j.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();
        Junction turnout=junctions.stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        var diamondRequest=new DiamondGeometry.Request(diamond,s,p.tune(s),PointMesh.extent(diamond,s));
        for(double offset:new double[]{.075,.4}){
            PointSettings raised=s.with(11,offset).with(18,5);
            require(!raised.movableFrog(),"The fixed-Y guard fixture must stay a plain (movableFrog=false) turnout");
            double top=p.top()+offset;
            // 75 mm is under one rail height, so the two sections still interpenetrate; 400 mm is a
            // whole section clear of the crossing rail.
            boolean overlaps=offset<=p.railHeight();
            var runs=GuardRails.forJunction(turnout,raised,p);
            require(!runs.isEmpty(),"The fixed-Y guard fixture produced no check runs at "+offset);
            require(crossesACrossingRail(runs,diamond,p),"The fixed-Y guard fixture has no guard across a crossing rail at "+offset);
            // The guard's own style group, and the fallback bake of the same guard: the two paths
            // the assembly can reach for it. Only the crossed rails' cutters differ.
            Mesh ownGroup=DiamondGeometry.combine(List.of(DiamondGeometry.fixedY(turnout,raised,p.tune(raised),PointMesh.extent(turnout,raised)).orElseThrow()),runs);
            Mesh oracle=DiamondGeometry.combine(List.of(diamondRequest),runs);
            var diamondView=PointRenderer.view(diamond,s,p,PointMesh.build(diamond,s,p,0,RailSampler.yBoundary(diamond,s)),null);
            var turnoutView=PointRenderer.view(turnout,raised,p,PointMesh.build(turnout,raised,p,0,RailSampler.yBoundary(turnout,raised)),null);
            Mesh assembly=PointRenderer.assembleForTest(List.of(diamondView,turnoutView));
            // Negative control: the same component with the crossing view removed. Nothing may cut
            // the guard there, so every sample the guard's own group covers must survive.
            Mesh withoutCrossing=PointRenderer.assembleForTest(List.of(turnoutView));
            var ownBands=bands(ownGroup,top);
            var assemblyBands=bands(assembly,top);
            var oracleBands=bands(oracle,top);
            var controlBands=bands(withoutCrossing,top);
            int covered=0,sharedCovered=0,cutByAssembly=0,cutByOracle=0,sharedCutByAssembly=0,sharedCutByOracle=0,cutWithoutCrossing=0,crossings=0;
            for(var run:runs)for(Track cutter:diamond.tracks()){
                if(cutter.id.equals(run.road().id))continue;
                // A run that lies on one of the crossing's own roads is baked by the fallback from
                // the same rails, so its oracle is exact; a run on the third road is not in the
                // oracle's component at all and is only required to be cut somewhere.
                boolean shared=diamond.tracks().stream().anyMatch(track->track.id.equals(run.road().id));
                for(int side:new int[]{-1,1}){
                    V3 centre=crossingPoint(run,cutter,side*p.centerOffset(),p);
                    if(centre==null)continue;
                    crossings++;
                    V3 along=horizontal(run.road().tangent(run.road().nearest(centre))),across=along.lateral();
                    // A quarter of a metre along the guard and 15 cm across it, around the point
                    // where the crossing rail's section covers the guard's own head band. Only the
                    // samples inside that crossing rail's head band can be cut at all, so the
                    // fallback bake of the same guard is an exact oracle there.
                    for(int i=-20;i<=20;i++)for(int k=-20;k<=20;k++){
                        V3 point=centre.add(along.mul(i*.0125)).add(across.mul(k*.007));
                        double at=cutter.nearest(point);
                        double beta=point.sub(cutter.at(at).add(cutter.tangent(at).lateral().mul(side*p.centerOffset()))).dot(horizontal(cutter.tangent(at)).lateral());
                        if(Math.abs(beta)>p.headWidth()/2-.008)continue;
                        if(column(ownBands,point)<1)continue;
                        covered++;
                        if(column(controlBands,point)<1)cutWithoutCrossing++;
                        boolean assemblyCut=column(assemblyBands,point)<1,oracleCut=column(oracleBands,point)<1;
                        if(assemblyCut)cutByAssembly++;
                        if(oracleCut)cutByOracle++;
                        if(shared){sharedCovered++;if(assemblyCut)sharedCutByAssembly++;if(oracleCut)sharedCutByOracle++;}
                    }
                }
            }
            double ownArea=topArea(ownGroup,top),assemblyArea=topArea(assembly,top);
            require(crossings>0&&covered>200,overlaps?"The fixed-Y guard fixture is too thin to prove anything: "+covered+" covered samples over "+crossings+" crossings"
                :"The clear-over fixed-Y guard fixture is too thin to prove anything: "+covered+" covered samples");
            // The control: taking the crossing component away leaves every sample of the guard.
            require(cutWithoutCrossing==0,"The fixed-Y guard lost "+cutWithoutCrossing+" samples with no crossing in its component at all");
            if(overlaps){
                // The two sections still interpenetrate at 75 mm, so the crossing rail must remove
                // guard steel exactly as the fallback bake of the same guard already does.
                require(cutByOracle>0,"The fallback bake of the fixed-Y guard performed no crossing cut to compare against: "+cutByOracle+" of "+covered+" samples");
                require(cutByAssembly>0,"A pooled fixed-Y guard whose height matches no crossing style group is not cut by the crossing rail it crosses: "+cutByAssembly+" of "+covered+" samples removed");
                require(sharedCovered>150,"The shared-road fixed-Y guard fixture is too thin to compare against the fallback: "+sharedCovered);
                require(sharedCutByAssembly==sharedCutByOracle,"The pooled fixed-Y guard cut "+sharedCutByAssembly+" of "+sharedCovered+" shared-road samples where the fallback bake of the same guard cuts "+sharedCutByOracle);
                require(assemblyArea<ownArea-1e-6,"The pooled fixed-Y guard kept its whole head where the crossing rail covers it: "+assemblyArea+" vs "+ownArea+" m2");
                require(cutByAssembly<covered,"The pooled fixed-Y guard was cut away entirely instead of only where the crossing rail covers it: "+cutByAssembly+" of "+covered+" samples removed");
            } else {
                // A guard more than one section above runs clear over the crossing rail: it must
                // keep exactly the steel its own group bakes, sample for sample and face for face.
                require(cutByAssembly==0&&cutByOracle==0,"A fixed-Y guard running clear above the crossing was cut: "+cutByAssembly+" assembly / "+cutByOracle+" fallback samples removed of "+covered);
                require(Math.abs(assemblyArea-ownArea)<1e-9,"A fixed-Y guard running clear above the crossing lost steel: "+assemblyArea+" vs "+ownArea+" m2");
            }
        }
    }

    /** Guard steel at one height: the faces whose column at the guard's head level is real steel,
     * so neighbour wing steel elsewhere in the window cannot mask a missing guard face. */
    private static List<Mesh.Quad> bands(Mesh mesh,double top){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            if(!q.part().equals("guard"))continue;
            double lo=Double.MAX_VALUE,hi=-Double.MAX_VALUE;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){lo=Math.min(lo,v.y());hi=Math.max(hi,v.y());}
            if(hi<top-.05||lo>top+.002)continue;
            result.add(q);
        }
        return result;
    }
    private static int column(List<Mesh.Quad> faces,V3 point){
        int count=0;
        for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))count++;
        return count;
    }

    /** Does any of these runs pass through a crossing rail's head band? */
    private static boolean crossesACrossingRail(List<GuardRails.Run> runs,Junction diamond,Profile p){
        for(var run:runs)for(Track cutter:diamond.tracks()){
            if(cutter.id.equals(run.road().id))continue;
            for(int side:new int[]{-1,1})if(crossingPoint(run,cutter,side*p.centerOffset(),p)!=null)return true;
        }
        return false;
    }
    /** {samples the uncut bake covers and the pooled bake drops, samples both keep}, swept over the
     * crossing rail's head band where it covers a guard's own head footprint. */
    private static int[] sample(List<GuardRails.Run> runs,Junction diamond,Profile p,double top,
                                List<Mesh.Quad> uncut,List<Mesh.Quad> pooled){
        int dropped=0,kept=0;
        for(var run:runs)for(Track cutter:diamond.tracks()){
            if(cutter.id.equals(run.road().id))continue;
            for(int side:new int[]{-1,1}){
                V3 centre=crossingPoint(run,cutter,side*p.centerOffset(),p);
                if(centre==null)continue;
                V3 along=horizontal(run.road().tangent(run.road().nearest(centre))),across=along.lateral();
                for(int i=-25;i<=25;i++)for(int k=-25;k<=25;k++){
                    V3 point=centre.add(along.mul(i*.01)).add(across.mul(k*.006));
                    double at=run.road().nearest(point);
                    double alpha=point.sub(run.road().at(at)).dot(horizontal(run.road().tangent(at)).lateral())-run.offset();
                    if(Math.abs(alpha)>p.headWidth()/2-.008)continue;
                    if(coverage(uncut,point)<1)continue;
                    if(coverage(pooled,point)<1)dropped++;else kept++;
                }
            }
        }
        return new int[]{dropped,kept};
    }
    /** Flat check steel at one height. */
    private static List<Mesh.Quad> topsAt(Mesh mesh,double top){
        var out=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            if(!q.part().equals("guard")&&!q.part().equals("wing"))continue;
            boolean flat=true;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(Math.abs(v.y()-top)>1e-9){flat=false;break;}
            if(flat)out.add(q);
        }
        return out;
    }

    /** Horizontal footprint of the check steel whose top faces sit at one height. */
    private static double topArea(Mesh mesh,double top){
        double area=0;
        for(var q:mesh.quads){
            if(!q.part().equals("guard"))continue;
            boolean flat=true;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(Math.abs(v.y()-top)>1e-9){flat=false;break;}
            if(flat)area+=Math.abs(V3.crossXZ(q.c().sub(q.a()),q.d().sub(q.b())))*.5;
        }
        return area;
    }

    /** The point where the run's own centre line passes through the crossing rail's head band. */
    private static V3 crossingPoint(GuardRails.Run run,Track cutter,double cutterOffset,Profile p){
        double bestDistance=Double.MAX_VALUE;V3 best=null;
        for(double d=run.start();d<=run.end();d+=.005){
            V3 guard=run.center(d);
            double e=cutter.nearest(guard);
            V3 centre=cutter.at(e).add(cutter.tangent(e).lateral().mul(cutterOffset));
            double distance=Math.hypot(centre.x()-guard.x(),centre.z()-guard.z());
            if(distance<bestDistance){bestDistance=distance;best=guard;}
        }
        return bestDistance<=p.headWidth()/2-.012?best:null;
    }
    private static V3 horizontal(V3 v){return new V3(v.x(),0,v.z()).unit();}
    private static List<Mesh.Quad> tops(Mesh mesh){
        var out=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            if(!q.part().equals("guard")&&!q.part().equals("wing"))continue;
            boolean flat=true;
            for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(Math.abs(v.y()-TOP)>1e-8){flat=false;break;}
            if(flat)out.add(q);
        }
        return out;
    }
    private static int coverage(List<Mesh.Quad> faces,V3 point){int count=0;for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))count++;return count;}
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
}
