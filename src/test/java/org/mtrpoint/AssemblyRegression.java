package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

final class AssemblyRegression {
    static void run()throws Exception{
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        Track a=Regression.line("guard-a","a","b",new V3(0,0,0),new V3(0,0,12));
        var points=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.1;points.add(new V3(z<6?0:.012*(z-6)*(z-6),0,z));}
        Track b=new Track("guard-b","c","d",points);
        var runs=List.of(new GuardRails.Run(a,2,10,.6,true,true,p,s),new GuardRails.Run(b,4,11,.6,true,true,p,s));
        Mesh guards=DiamondGeometry.guards(runs);var tops=DiamondRegression.tops(guards,p.top());
        int samples=0;
        for(double z=4.5;z<10;z+=.053)for(double x=-.72;x<-.27;x+=.0071){
            V3 q=new V3(x,0,z);int hits=DiamondRegression.coverage(tops,q);
            if(hits>1)throw new AssertionError("Partially coincident guards have overlapping heads at "+q);samples++;
        }
        for(double z=4.5;z<5.5;z+=.073)if(DiamondRegression.coverage(tops,new V3(-.6,0,z))!=1)throw new AssertionError("Coincident guard union removed both rails");
        var shortPoints=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.1;shortPoints.add(new V3(z<=6?0:.02*(z-6)*(z-6),0,z));}
        Track shortOverlap=new Track("guard-short","e","f",shortPoints);
        Mesh joined=DiamondGeometry.guards(List.of(new GuardRails.Run(a,2,6,.6,true,true,p,s),new GuardRails.Run(shortOverlap,5.8,10,.6,true,true,p,s)));
        var joinedTops=DiamondRegression.tops(joined,p.top());
        for(double z=5.82;z<5.98;z+=.023)if(DiamondRegression.coverage(joinedTops,new V3(-.6,0,z))!=1)throw new AssertionError("Short guard overlap retained an internal flare at "+z);
        Regression.export(guards,"guards-partial-union");
        var commonPoints=new ArrayList<V3>();var earlyPoints=new ArrayList<V3>();var latePoints=new ArrayList<V3>();
        for(int i=0;i<=240;i++){double z=i*.125;commonPoints.add(new V3(0,0,z));earlyPoints.add(new V3(.012*z*z,0,z));latePoints.add(new V3(.012*Math.pow(Math.max(0,z-7),2),0,z));}
        Track common=new Track("shared","node","through",commonPoints),early=new Track("early","node","early-end",earlyPoints),late=new Track("late","node","late-end",latePoints);
        Junction firstTurnout=new Junction("first",Junction.Kind.Y,common,early,new V3(0,0,0),0,0,30),secondTurnout=new Junction("second",Junction.Kind.Y,common,late,new V3(0,0,0),0,0,30);
        Mesh overlaid=new Mesh();
        for(Mesh source:List.of(PointMesh.build(firstTurnout,s,p,0),PointMesh.build(secondTurnout,s,p,0)))for(var q:source.quads)if(q.part().equals("rail"))overlaid.quad(q);
        var firstCut=RailCuts.forJunction(firstTurnout,s,p).stream().filter(c->c.road().id.equals(common.id)).findFirst().orElseThrow();double cutAt=(firstCut.start()+firstCut.end())/2;
        double sharedStart=Math.max(0,firstCut.start()-3),sharedEnd=Math.min(common.length,firstCut.end()+3);
        overlaid.rail(common.at(sharedStart).add(common.tangent(sharedStart).lateral().mul(firstCut.offset())),common.at(sharedEnd).add(common.tangent(sharedEnd).lateral().mul(firstCut.offset())),1,1,p.tune(s),s,"rail",new Mesh.RailTag(common,sharedStart,sharedEnd,firstCut.offset()));
        V3 gap=common.at(cutAt).add(common.tangent(cutAt).lateral().mul(firstCut.offset()));
        if(DiamondRegression.coverage(DiamondRegression.tops(overlaid,p.top()),gap)==0)throw new AssertionError("Overlap fixture did not refill the first turnout gap");
        var allCuts=new ArrayList<RailCuts.Cut>();allCuts.addAll(RailCuts.forJunction(firstTurnout,s,p));allCuts.addAll(RailCuts.forJunction(secondTurnout,s,p));
        var segments=overlaid.quads.stream().filter(q->q.part().equals("rail")).map(Mesh.Quad::rail).filter(Objects::nonNull).map(Mesh.RailTag::canonical).map(tag->new RailCuts.Segment(tag.road(),tag.start(),tag.end(),tag.offset(),p.tune(s),s)).toList();
        Mesh coordinated=RailCuts.assemble(segments,new Mesh(),allCuts);
        if(DiamondRegression.coverage(DiamondRegression.tops(coordinated,p.top()),gap)!=0)throw new AssertionError("Neighbor turnout rail refilled a shared crossing gap");
        Mesh longRail=new Mesh();V3 railA=common.at(Math.max(0,firstCut.start()-3)).add(common.tangent(firstCut.start()).lateral().mul(firstCut.offset())),railB=common.at(Math.min(common.length,firstCut.end()+3)).add(common.tangent(firstCut.end()).lateral().mul(firstCut.offset()));longRail.rail(railA,railB,1,1,p,s,"rail");
        Mesh longCut=RailCuts.apply(longRail,List.of(firstCut));var longTops=DiamondRegression.tops(longCut,p.top());
        if(DiamondRegression.coverage(longTops,gap)!=0)throw new AssertionError("A long rail face whose centre lies outside the crossing was not cut");
        for(double end:new double[]{firstCut.start(),firstCut.end()}){V3 plane=common.at(end);if(longCut.quads.stream().noneMatch(q->q.part().equals("rail")&&List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.sub(plane).dot(common.tangent(end)))<1e-7)))throw new AssertionError("Rail cut has no flat profile cap at "+end);}
        Mesh taggedRail=new Mesh();taggedRail.rail(common.at(3).add(common.tangent(3).lateral().mul(firstCut.offset())),common.at(9).add(common.tangent(9).lateral().mul(firstCut.offset())),1,1,p,s,"rail",new Mesh.RailTag(common,3,9,firstCut.offset()));
        var overlappingCuts=List.of(new RailCuts.Cut(common,4,6,firstCut.offset(),.125,p,s),new RailCuts.Cut(common,5,7,firstCut.offset(),.125,p,s));
        Mesh onePocket=RailCuts.apply(taggedRail,overlappingCuts);var pocketTops=DiamondRegression.tops(onePocket,p.top());
        for(double d=4.02;d<6.98;d+=.071)if(DiamondRegression.coverage(pocketTops,common.at(d).add(common.tangent(d).lateral().mul(firstCut.offset())))!=0)throw new AssertionError("Overlapping cuts left an internal rail fragment at "+d);
        for(double internal:new double[]{5,6}){V3 plane=common.at(internal);if(onePocket.quads.stream().anyMatch(q->q.part().equals("rail")&&List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.sub(plane).dot(common.tangent(internal)))<1e-7)))throw new AssertionError("Overlapping cuts retained an internal cap at "+internal);}
        Track samePlace=Regression.line("same-place","other-a","other-b",new V3(0,0,0),new V3(0,0,30));double fixedOffset=p.centerOffset();
        var separated=List.of(new RailCuts.Segment(common,0,10,fixedOffset,p,s),new RailCuts.Segment(common,12.5,20,fixedOffset,p,s));
        Mesh positiveGap=RailCuts.assemble(separated,new Mesh(),List.of());
        if(DiamondRegression.coverage(DiamondRegression.tops(positiveGap,p.top()),common.at(11).add(common.tangent(11).lateral().mul(fixedOffset)))!=0)throw new AssertionError("A positive fixed-rail segment gap was bridged");
        Mesh adjacent=RailCuts.assemble(List.of(new RailCuts.Segment(common,0,10,fixedOffset,p,s),new RailCuts.Segment(common,10,20,fixedOffset,p,s)),new Mesh(),List.of());
        for(double station:new double[]{9.99,10.01})if(DiamondRegression.coverage(DiamondRegression.tops(adjacent,p.top()),common.at(station).add(common.tangent(station).lateral().mul(fixedOffset)))!=1)throw new AssertionError("Exactly adjacent fixed-rail segments were not continuous at "+station);
        RailCuts.Cut exactCut=new RailCuts.Cut(common,9.5,13,fixedOffset,.12,p,s);
        Mesh exactCutRail=RailCuts.assemble(List.of(new RailCuts.Segment(common,0,20,fixedOffset,p,s)),new Mesh(),List.of(exactCut));
        if(DiamondRegression.coverage(DiamondRegression.tops(exactCutRail,p.top()),common.at(11).add(common.tangent(11).lateral().mul(fixedOffset)))!=0)throw new AssertionError("Same-ID fixed-rail cut did not remove its exact interval");
        RailCuts.Cut foreignCut=new RailCuts.Cut(samePlace,9.5,13,fixedOffset,.12,p,s);
        Mesh foreignCutRail=RailCuts.assemble(List.of(new RailCuts.Segment(common,0,20,fixedOffset,p,s)),new Mesh(),List.of(foreignCut));
        if(DiamondRegression.coverage(DiamondRegression.tops(foreignCutRail,p.top()),common.at(11).add(common.tangent(11).lateral().mul(fixedOffset)))!=1)throw new AssertionError("An identical-geometry foreign track ID cut fixed rail");
        Mesh nodeCentred=PointMesh.build(firstTurnout,s,p,0);double branchOuter=TurnoutFrame.side(firstTurnout,PointMesh.extent(firstTurnout,s))*p.centerOffset();
        double branchStart=nodeCentred.quads.stream().map(Mesh.Quad::rail).filter(Objects::nonNull).filter(t->t.road().id.equals(early.id)&&Math.abs(t.offset()-branchOuter)<1e-6).mapToDouble(Mesh.RailTag::start).min().orElseThrow();
        if(branchStart>1e-8)throw new AssertionError("Outer branch rail does not follow its curve from the common node: "+branchStart);
        var offsetRuns=GuardRails.merge(List.of(new GuardRails.Run(common,2,7,.6,true,true,p,s),new GuardRails.Run(common,6,10,.61,true,true,p,s)));
        if(offsetRuns.size()!=2)throw new AssertionError("Distinct same-road guard offsets were merged");
        Track guardLeft=Regression.line("guard-left","gl0","gl1",new V3(0,0,0),new V3(0,0,8)),guardRight=Regression.line("guard-right","gr0","gr1",new V3(0,0,6),new V3(0,0,14));
        var splitGuard=GuardRails.merge(List.of(new GuardRails.Run(guardLeft,1,8,.6,true,true,p,s),new GuardRails.Run(guardRight,0,7,.6,true,true,p,s)));
        if(splitGuard.size()!=2)throw new AssertionError("Finite tracks were synthetically stitched into one guard rail");
        var divergingGuard=GuardRails.merge(List.of(new GuardRails.Run(a,2,10,.6,true,true,p,s),new GuardRails.Run(b,4,11,.6,true,true,p,s)));
        if(divergingGuard.size()!=2)throw new AssertionError("Partially coincident diverging cross-ID guards were rebased");
        var steel=Profile.STEEL;var sideFaces=List.of(
            new Mesh.Quad(new V3(-.07,0,0),new V3(-.07,.16,0),new V3(-.07,.16,1),new V3(-.07,0,1),steel,"rail",-1),
            new Mesh.Quad(new V3(.07,0,0),new V3(.07,0,1),new V3(.07,.16,1),new V3(.07,.16,0),steel,"rail",-1),
            new Mesh.Quad(new V3(-.07,.16,0),new V3(.07,.16,0),new V3(.07,.16,1),new V3(-.07,.16,1),steel,"rail",-1),
            new Mesh.Quad(new V3(-.07,0,0),new V3(-.07,0,1),new V3(.07,0,1),new V3(.07,0,0),steel,"rail",-1));
        ModelDetail openDetail=new ModelDetail(sideFaces,List.of(),List.of(),0,.16,.14,0,1,0,.0,false);Profile detailed=new Profile(1.435,.264,.068,.14,.165,steel,Profile.TIMBER,"open-ended",false,openDetail);
        Mesh capped=new GuardRails.Run(a,2,5,.6,true,true,detailed,s).mesh();
        for(double end:new double[]{2,5})if(capped.quads.stream().noneMatch(q->q.part().equals("guard")&&List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Math.abs(v.z()-end)<1e-8)))throw new AssertionError("Custom check rail has an open end at "+end);
        Mesh detailedFrog=new Mesh();new FrogGeometry(firstTurnout,s,detailed,PointMesh.extent(firstTurnout,s)).build(detailedFrog,0);
        var wingCaps=detailedFrog.quads.stream().filter(q->q.part().equals("wing")&&q.c().distance(q.d())<1e-10).toList();
        if(wingCaps.size()<4||wingCaps.stream().anyMatch(q->q.uv()==null||q.uv().size()!=8))throw new AssertionError("Custom wing rails do not have textured exposed end caps");
        Mesh supports=new Mesh();supports.beam(new V3(-2,0,0),new V3(2,0,0),.3,.3,0,.12,p.sleeper(),"sleeper",1);
        supports.beam(new V3(-1,0,-.6),new V3(1,0,.6),.3,.3,0,.12,p.sleeper(),"sleeper",2);
        var original=DiamondRegression.tops(supports,.12);Mesh clean=SurfaceUnion.build(supports);var cleanTops=DiamondRegression.tops(clean,.12);
        for(double x=-2;x<2;x+=.029)for(double z=-.8;z<.8;z+=.031){V3 q=new V3(x,0,z);int before=DiamondRegression.coverage(original,q),after=DiamondRegression.coverage(cleanTops,q);if((before>0)!=(after>0)||after>1)throw new AssertionError("Support union changed coverage or retained duplicate faces");}
        Mesh duplicate=new Mesh();duplicate.quads.addAll(supports.quads);duplicate.quads.addAll(supports.quads);
        if(!SurfaceUnion.build(duplicate).quads.equals(clean.quads))throw new AssertionError("Duplicate support assembly is drawn twice");
        var group=ScissorsLayout.find(Detector.find(Regression.scissors(false))).get(0);
        Mesh center=group.centerMesh(s,p);boolean diagonal=false;
        for(var q:center.quads)if(q.part().equals("sleeper"))for(V3 edge:List.of(q.b().sub(q.a()),q.d().sub(q.a())))
            if(edge.length()>.15&&Math.abs(edge.unit().dot(group.axis()))>.08&&Math.abs(edge.unit().dot(group.axis()))<.5)diagonal=true;
        if(!diagonal)throw new AssertionError("Scissors V mode ignores diagonal roads");
        Track crossA=Regression.line("pitch-a","pa","pb",new V3(0,0,-20),new V3(0,0,20));
        Track crossB=Regression.line("pitch-b","pc","pd",new V3(-20,0,0),new V3(20,0,0));
        Mesh ties=new Mesh();VSleepers.diamond(ties,new Junction("pitch",Junction.Kind.DIAMOND,crossA,crossB,new V3(0,0,0),20,20,8),s,p,8);
        var tieTops=DiamondRegression.tops(ties,p.top()-p.railHeight());boolean covered=false;var starts=new ArrayList<Double>();
        for(double z=2;z<7;z+=.005){int hits=DiamondRegression.coverage(tieTops,new V3(.01,0,z));if(hits>1)throw new AssertionError("Independent diamond has overlapping bearer tops");if(hits>0&&!covered)starts.add(z);covered=hits>0;}
        if(starts.size()<6)throw new AssertionError("Diamond sleepers missing along a road");
        for(int i=2;i<starts.size();i++)if(Math.abs(starts.get(i)-starts.get(i-1)-s.sleeperSpacing())>.015)throw new AssertionError("Diamond sleeper pitch is compressed");
        var graded=new ArrayList<V3>();for(int i=0;i<=200;i++){double z=i*.1;graded.add(new V3(.006*z*z,.2*z,z));}
        Track grade=new Track("grade","g0","g1",graded);V3 origin=grade.at(4),forward=grade.tangent(4);
        V3 horizontal=new V3(forward.x(),0,forward.z()).unit();V3 first=null,last=null;
        for(double pos:new double[]{0,.25,.5,.75,1}){
            V3 contact=TurnoutFrame.contact(grade,grade,4,1,p.centerOffset(),p.headWidth(),pos,0,8,s,origin,forward);
            if(Math.abs(contact.sub(origin).dot(horizontal))>1e-7)throw new AssertionError("Graded moving stretcher is skewed in plan");
            if(first==null)first=contact;last=contact;
        }
        if(first.distance(last)<.02)throw new AssertionError("Stretcher does not follow the blade");
        wingIntervals();
        sharedFixedY();
        cutFrames();
        externalGuardWingJoin();
        incomingWingExcluded();
        System.out.println("PASS: partial/short guard union "+samples+" samples; coordinated tagged rail cuts and node-centred outer rails; guard/wing end caps; cross-ID guard/wing join; final support coverage/duplicates; diagonal V arms; graded moving stretcher");
    }
    /** PointRenderer pools a crossing's fixed check steel with every adjoining turnout's guard
     * rail. A guard that physically joins a check-side wing at a real offset-rail endpoint must
     * close into one continuous rail: the joint is interior steel, so the wing's terminal mouth
     * (cap and 10 cm inward flare) must not survive there. */
    private static void externalGuardWingJoin(){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        Track main=Regression.line("xg-main","xm0","xm1",new V3(-20,0,0),new V3(20,0,0));
        Track cross=Regression.line("xg-cross","xc0","xc1",new V3(0,0,-20),new V3(0,0,20));
        Junction diamond=Detector.find(List.of(main,cross)).stream().filter(j->j.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();
        double extent=PointMesh.extent(diamond,s),center=main.nearest(diamond.center());
        var request=new DiamondGeometry.Request(diamond,s,p.tune(s),extent);
        double checkOffset=p.centerOffset()-p.headWidth()-Math.max(.02,s.flangeway()+s.wingGapDelta());
        Mesh alone=DiamondGeometry.combine(List.of(request));
        var aloneTops=DiamondRegression.tops(alone,p.top());
        // The crossing's own check rail ends at a real offset-rail endpoint with a terminal flare.
        double terminal=-1;
        for(double d=.2;d<Math.min(main.length,center+extent);d+=.01){
            V3 point=main.at(d).add(main.tangent(d).lateral().mul(checkOffset));
            if(DiamondRegression.coverage(aloneTops,point)>0)terminal=d;
        }
        if(terminal<0)throw new AssertionError("Fixture could not locate the crossing's own check rail");
        // The adjoining turnout's guard rail arrives on a different Track ID, coincident with the
        // crossing road, and overlaps the terminal mouth only partially.
        Track neighbour=new Track("xg-neighbour","xn0","xn1",main.points);
        double join=terminal-.6,far=Math.min(main.length,center+8);
        var external=new GuardRails.Run(neighbour,join,far,checkOffset,true,true,p,s);
        Mesh pooled=DiamondGeometry.combine(List.of(request),List.of(external));
        var pooledTops=DiamondRegression.tops(pooled,p.top());
        int mouth=0;
        for(double d=terminal-.18;d<terminal+.35;d+=.02){
            V3 line=main.at(d).add(main.tangent(d).lateral().mul(checkOffset));
            int faces=DiamondRegression.coverage(pooledTops,line);
            if(faces!=1)throw new AssertionError("Joined cross-ID check rail is not continuous at "+d+": faces="+faces);
            V3 flare=main.at(d).add(main.tangent(d).lateral().mul(checkOffset-.085));
            if(DiamondRegression.coverage(pooledTops,flare)!=0)throw new AssertionError("Joined check rail kept its internal terminal flare at "+d);
            if(DiamondRegression.coverage(aloneTops,flare)>0)mouth++;
        }
        if(mouth==0)throw new AssertionError("Fixture did not reproduce the unjoined rail's terminal flare");
        // The pooled guard really continues past the endpoint instead of stopping at the mouth.
        for(double d=terminal+.4;d<terminal+2;d+=.1){
            V3 point=main.at(d).add(main.tangent(d).lateral().mul(checkOffset));
            if(DiamondRegression.coverage(pooledTops,point)!=1)throw new AssertionError("Pooled guard rail does not continue past the offset-rail endpoint at "+d);
        }
        System.out.println("PASS: cross-ID guard joins the check rail at an offset-rail endpoint without an interior mouth ("+mouth+" unjoined flare samples)");
    }
    /** A fixed Y's incoming wings ride the crossing's own running rails and are frog steel; only
     * the check-side wings may be absorbed into the shared check pool. */
    private static void incomingWingExcluded(){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        Track common=Regression.line("iw-main","iw0","iw1",new V3(0,0,0),new V3(0,0,30));
        var branchPoints=new ArrayList<V3>();for(int i=0;i<=240;i++){double z=i*.125;branchPoints.add(new V3(.0105*z*z,0,z));}
        Track branch=new Track("iw-branch","iw0","iw2",branchPoints);
        Junction y=new Junction("iw",Junction.Kind.Y,common,branch,new V3(0,0,0),0,0,30);
        double extent=PointMesh.extent(y,s);
        var wings=new FrogGeometry(y,s,p,extent).fixedWings();
        double checkOffset=p.centerOffset()-p.headWidth()-Math.max(.02,s.flangeway()+s.wingGapDelta());
        var incoming=wings.stream().filter(w->Math.abs(Math.abs(w.offset())-p.centerOffset())<1e-9).toList();
        var checks=wings.stream().filter(w->Math.abs(Math.abs(w.offset())-checkOffset)<1e-9).toList();
        if(incoming.size()!=2||checks.size()!=2)throw new AssertionError("Fixed Y must expose two incoming and two check-side wings");
        DiamondGeometry.Request request=DiamondGeometry.fixedY(y,s,p,extent).orElseThrow();
        // Any same-style guard rail forces the pooled assembly; keep it far from the turnout.
        Track elsewhere=Regression.line("iw-far","iw-f0","iw-f1",new V3(200,0,0),new V3(200,0,20));
        Mesh pooled=DiamondGeometry.combine(List.of(request),List.of(new GuardRails.Run(elsewhere,2,12,.6,true,true,p,s)));
        Mesh legacy=DiamondGeometry.combine(List.of(request));
        var pooledTops=DiamondRegression.tops(pooled,p.top());
        var legacyTops=DiamondRegression.tops(legacy,p.top());
        // An incoming fixed-Y wing physically coincides with the frog's own running steel, so the
        // pooled bake may keep that one face under the frog label. Behaviour is therefore compared
        // against the legacy assembly rather than the internal part tag.
        for(var wing:incoming){
            int samples=0;
            for(double t=.1;t<.95;t+=.1){
                double at=wing.start()+(wing.flareAt()-wing.start())*t;
                V3 rail=wing.road().at(at).add(wing.road().tangent(at).lateral().mul(wing.offset()));
                int want=DiamondRegression.coverage(legacyTops,rail);
                if(want<1)continue;
                int got=DiamondRegression.coverage(pooledTops,rail);
                if(got<1)throw new AssertionError("Incoming fixed-Y wing lost its steel in the pooled assembly on "+wing.road().id+" at "+at);
                if(got>Math.max(1,want))throw new AssertionError("Incoming fixed-Y wing duplicated steel in the pooled assembly on "+wing.road().id+" at "+at);
                samples++;
            }
            if(samples==0)throw new AssertionError("Fixture has no legacy steel on incoming run "+wing.road().id);
            // The pool must never lay guard steel on the incoming run where the legacy assembly
            // has no steel at all: that is the observable form of absorbing the wing.
            for(var q:pooled.quads)if(q.part().equals("guard")&&onRun(wing,wing.offset(),q)){
                V3 center=q.center();double at=wing.road().nearest(center);
                V3 line=wing.road().at(at).add(wing.road().tangent(at).lateral().mul(wing.offset()));
                if(DiamondRegression.coverage(legacyTops,line)==0)
                    throw new AssertionError("Pooled guard swallowed the incoming fixed-Y wing on "+wing.road().id+" at "+at);
            }
        }
        for(var wing:checks){
            if(pooled.quads.stream().noneMatch(q->q.part().equals("guard")&&onRun(wing,wing.offset(),q)))
                throw new AssertionError("Fixed-Y check-side wing did not join the shared check pool on "+wing.road().id);
        }
        System.out.println("PASS: fixed-Y incoming frog wings stay out of the shared check pool");
    }
    /** Does the face lie on the run's own offset curve inside its interval? */
    private static boolean onRun(FrogGeometry.WingRun wing,double offset,Mesh.Quad q){
        V3 center=q.center();double at=wing.road().nearest(center);
        if(at<wing.start()-.05||at>wing.flareAt()+.05)return false;
        double lateral=center.sub(wing.road().at(at)).dot(wing.road().tangent(at).lateral());
        return Math.abs(Math.abs(lateral)-Math.abs(offset))<.02;
    }
    private static void wingIntervals(){
        Track road=Regression.line("wing-merge","w0","w1",new V3(0,0,0),new V3(0,0,30));
        FrogGeometry.WingRun early=new FrogGeometry.WingRun(road,2,2.4,8,8.3,.45,.6,.45,true,true);
        FrogGeometry.WingRun late=new FrogGeometry.WingRun(road,7,7,12,12.5,.6,.6,.4,false,true);
        var merged=FrogGeometry.mergeWings(List.of(early,late));
        if(merged.size()!=1)throw new AssertionError("Overlapping same-road wing intervals did not merge");
        var union=merged.get(0);
        if(Math.abs(union.start()-2)>1e-8||Math.abs(union.startFlareAt()-2.4)>1e-8||Math.abs(union.endAt()-12)>1e-8||Math.abs(union.flareAt()-12.5)>1e-8||!union.capStart()||!union.capEnd())throw new AssertionError("Merged wing did not retain only exterior blends/caps");
        FrogGeometry.WingRun reversed=new FrogGeometry.WingRun(road.reverse(),road.length-12.5,road.length-12,road.length-7,road.length-7,-.4,-.6,-.6,true,false);
        var reverseMerged=FrogGeometry.mergeWings(List.of(early,reversed));
        if(reverseMerged.size()!=1||!sameWingInterval(reverseMerged.get(0),union))throw new AssertionError("Reversed wing interval did not merge identically: union="+union+" reverseMerged="+reverseMerged);
        FrogGeometry.WingRun differentOffset=new FrogGeometry.WingRun(road,7,7,12,12.5,.61,.61,.4,false,true);
        FrogGeometry.WingRun positiveGap=new FrogGeometry.WingRun(road,12.50001,12.50001,14,14,.6,.6,.6,false,false);
        if(FrogGeometry.mergeWings(List.of(early,differentOffset)).size()!=2)throw new AssertionError("Different wing offsets merged");
        if(FrogGeometry.mergeWings(List.of(early,positiveGap)).size()!=2)throw new AssertionError("Positive wing gap merged");
    }
    private static boolean sameWingInterval(FrogGeometry.WingRun a,FrogGeometry.WingRun b){
        return a.road().id.equals(b.road().id)&&a.road().startNode.equals(b.road().startNode)&&a.road().endNode.equals(b.road().endNode)
            &&Math.abs(a.start()-b.start())<=1e-8&&Math.abs(a.startFlareAt()-b.startFlareAt())<=1e-8
            &&Math.abs(a.endAt()-b.endAt())<=1e-8&&Math.abs(a.flareAt()-b.flareAt())<=1e-8
            &&Math.abs(a.startOffset()-b.startOffset())<=1e-8&&Math.abs(a.offset()-b.offset())<=1e-8
            &&Math.abs(a.endOffset()-b.endOffset())<=1e-8&&a.capStart()==b.capStart()&&a.capEnd()==b.capEnd();
    }
    private static void sharedFixedY(){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        Track common=Regression.line("fixed-y-main","fy0","fy1",new V3(0,0,0),new V3(0,0,30));
        var branchPoints=new ArrayList<V3>();for(int i=0;i<=240;i++){double z=i*.125;branchPoints.add(new V3(.0105*z*z,0,z));}
        Track branch=new Track("fixed-y-branch","fy0","fy2",branchPoints);
        Junction y=new Junction("fixed-y",Junction.Kind.Y,common,branch,new V3(0,0,0),0,0,30),other=new Junction("fixed-y-other",Junction.Kind.Y,common,new Track("fixed-y-branch-other","fy0","fy3",branchPoints.stream().map(v->new V3(v.x()*1.04,0,v.z())).toList()),new V3(0,0,0),0,0,30);
        double extent=PointMesh.extent(y,s);FrogGeometry frog=new FrogGeometry(y,s,p,extent);
        DiamondGeometry.Request request=DiamondGeometry.fixedY(y,s,p,extent).orElseThrow();Mesh combined=DiamondGeometry.combine(List.of(request));var tops=DiamondRegression.tops(combined,p.top());
        var cuts=RailCuts.forJunction(y,s,p);
        for(int road=0;road<2;road++){
            Track track=road==0?common:branch;double side=TurnoutFrame.side(y,extent),sign=road==0?side:-side,offset=sign*p.centerOffset(),toe=frog.toe(road),heel=frog.heel(road);
            RailCuts.Cut cut=cuts.stream().filter(c->c.road().id.equals(track.id)&&Math.abs(c.offset()-offset)<1e-8).findFirst().orElseThrow();
            if(Math.abs(cut.start()-toe)>1e-8||Math.abs(cut.end()-heel)>1e-8)throw new AssertionError("Fixed Y request/cut stations diverged on "+track.id);
            for(double d:new double[]{toe+.01,heel-.01})if(DiamondRegression.coverage(tops,track.at(d).add(track.tangent(d).lateral().mul(offset)))==0)throw new AssertionError("Shared fixed Y has a handoff gap at "+track.id+" "+d);
            Mesh frogOnly=new Mesh();for(var q:combined.quads)if(q.part().equals("frog"))frogOnly.quad(q);
            for(double d:new double[]{toe-.01,heel+.01})if(DiamondRegression.coverage(DiamondRegression.tops(frogOnly,p.top()),track.at(d).add(track.tangent(d).lateral().mul(offset)))!=0)throw new AssertionError("Shared fixed Y frog overruns its exact interval at "+track.id+" "+d);
        }
        var firstFrog=new FrogGeometry(y,s,p,extent);
        var secondFrog=new FrogGeometry(other,s,p,extent);
        DiamondGeometry.Request otherRequest=DiamondGeometry.fixedY(other,s,p,extent).orElseThrow();
        if(Math.max(firstFrog.toe(0),secondFrog.toe(0))>=Math.min(firstFrog.heel(0),secondFrog.heel(0)))throw new AssertionError("Nearby Y fixture does not overlap");
        Mesh nearby=DiamondGeometry.combine(List.of(request,otherRequest));var nearbyTops=DiamondRegression.tops(nearby,p.top());
        for(var crossing:List.of(request,otherRequest))for(int road=0;road<2;road++){
            Track track=road==0?crossing.junction().a():crossing.junction().b();double side=TurnoutFrame.side(crossing.junction(),crossing.extent()),sign=road==0?side:-side;
            double d=(road==0?crossing.aStart()+crossing.aEnd():crossing.bStart()+crossing.bEnd())/2,gap=Math.max(.02,crossing.settings().flangeway()+crossing.settings().wingGapDelta());
            V3 channel=track.at(d).add(track.tangent(d).lateral().mul(sign*(p.centerOffset()-p.headWidth()/2-gap/2)));
            if(DiamondRegression.coverage(nearbyTops,channel)!=0)throw new AssertionError("Nearby fixed Y channel was refilled by another crossing at "+channel);
        }
        if(nearby.quads.stream().noneMatch(q->q.part().equals("frog"))||nearby.quads.stream().noneMatch(q->q.part().equals("wing")))throw new AssertionError("Shared fixed Y assembly lacks replacement frog or wings");
        if(DiamondGeometry.fixedY(y,s.flags(true,true),p,extent).isPresent())throw new AssertionError("Movable Y was promoted into fixed crossing assembly");
        Mesh moving0=PointMesh.build(y,s.flags(true,true),p,0),moving1=PointMesh.build(y,s.flags(true,true),p,1);
        if(moving0.quads.stream().filter(q->q.part().equals("frog")).toList().equals(moving1.quads.stream().filter(q->q.part().equals("frog")).toList()))throw new AssertionError("Movable Y no longer uses its animated frog path");
        System.out.println("PASS: exact fixed-Y handoffs, shared nearby-Y channels, and movable-Y exclusion");
    }
    /** A cut on a curved offset rail must plane and close in the offset curve's own frame. */
    private static void cutFrames(){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        var points=new ArrayList<V3>();for(int i=0;i<=240;i++){double z=i*.125;points.add(new V3(.009*z*z,0,z));}
        Track curved=new Track("curved-cut","cc0","cc1",points);
        double offset=p.centerOffset(),station=15,start=station-2,end=station+2;
        // A single straight chord is not the curved offset path it is tagged with; cell the
        // source interval so every face's geometry and its tag agree at cut scale.
        Mesh tagged=new Mesh();
        int cells=Math.max(1,(int)Math.ceil((26-4)/.24));double cell=(26D-4)/cells;
        for(int i=0;i<cells;i++){double a=4+cell*i,b=a+cell;
            tagged.rail(curved.at(a).add(curved.tangent(a).lateral().mul(offset)),curved.at(b).add(curved.tangent(b).lateral().mul(offset)),1,1,p,s,"rail",new Mesh.RailTag(curved,a,b,offset));}
        Mesh cutMesh=RailCuts.apply(tagged,List.of(new RailCuts.Cut(curved,start,end,offset,.125,p,s)));
        for(double boundary:new double[]{start,end})checkCutCap(cutMesh,curved,boundary,offset,p,s);
        var tops=DiamondRegression.tops(cutMesh,p.top());
        for(double d:new double[]{start-.06,end+.06})if(DiamondRegression.coverage(tops,curved.at(d).add(curved.tangent(d).lateral().mul(offset)))!=1)throw new AssertionError("Curved rail lost steel outside its crossing interval at "+d);
        for(double d:new double[]{station-1,station+1})if(DiamondRegression.coverage(tops,curved.at(d).add(curved.tangent(d).lateral().mul(offset)))!=0)throw new AssertionError("Curved rail was not removed inside its crossing interval at "+d);
        // The exact tagged interval path must cap the built-in profile at its hole too.
        RailCuts.Segment whole=new RailCuts.Segment(curved,0,20,offset,p,s);
        Mesh assembled=RailCuts.assemble(List.of(whole),new Mesh(),List.of(new RailCuts.Cut(curved,9.5,13,offset,.125,p,s)));
        checkCutCap(assembled,curved,9.5,offset,p,s);checkCutCap(assembled,curved,13,offset,p,s);
        var assembledTops=DiamondRegression.tops(assembled,p.top());
        for(double d:new double[]{9.44,13.06})if(DiamondRegression.coverage(assembledTops,curved.at(d).add(curved.tangent(d).lateral().mul(offset)))!=1)throw new AssertionError("Exact interval assembly lost steel beside its hole at "+d);
        for(double d:new double[]{10,11,12})if(DiamondRegression.coverage(assembledTops,curved.at(d).add(curved.tangent(d).lateral().mul(offset)))!=0)throw new AssertionError("Exact interval assembly left steel inside its hole at "+d);
        Mesh empty=RailCuts.apply(tagged,List.of(new RailCuts.Cut(curved,0,30,offset,.125,p,s)));
        if(empty.quads.stream().anyMatch(q->q.part().equals("rail")))throw new AssertionError("Full-interval cut left offset rail steel");
        System.out.println("PASS: curved offset-rail cut frames close built-in cross-sections for arbitrary cuts and exact intervals");
    }
    /** Every end face at a cut must be a coplanar cross-section of the offset curve. */
    private static void checkCutCap(Mesh mesh,Track road,double boundary,double offset,Profile p,PointSettings s){
        V3 expected=road.at(boundary).add(road.tangent(boundary).lateral().mul(offset));
        var caps=mesh.quads.stream().filter(q->q.rail()!=null&&Math.abs(q.rail().start()-boundary)<1e-9&&Math.abs(q.rail().end()-boundary)<1e-9).toList();
        if(caps.size()<3)throw new AssertionError("Built-in rail cut has no complete foot/web/head end face at "+boundary+": "+caps.size());
        double top=p.top()+s.verticalOffset();boolean head=false,foot=false;
        double area=0;
        for(var q:caps){
            V3 u=q.b().sub(q.a()),v=q.c().sub(q.a());
            double cx=u.y()*v.z()-u.z()*v.y(),cy=u.z()*v.x()-u.x()*v.z(),cz=u.x()*v.y()-u.y()*v.x();
            area+=Math.sqrt(cx*cx+cy*cy+cz*cz)/2;
            V3 n=new V3(u.y()*v.z()-u.z()*v.y(),u.z()*v.x()-u.x()*v.z(),u.x()*v.y()-u.y()*v.x()).unit();
            if(Math.abs(n.y())>.02)throw new AssertionError("Cut end face is not a vertical cross-section at "+boundary);
            V3 lateral=n.lateral();
            for(V3 vert:List.of(q.a(),q.b(),q.c())){
                if(Math.abs(vert.sub(expected).dot(n))>1e-9)throw new AssertionError("Cut end vertex is off the offset-curve plane at "+boundary);
                double side=Math.abs(vert.sub(expected).dot(lateral));
                if(Math.min(Math.abs(side-p.headWidth()/2),Math.min(Math.abs(side-p.footWidth()/2),Math.abs(side-.011)))>1e-9)
                    throw new AssertionError("Cut end face does not follow the modelled cross-section at "+boundary+": "+side);
                if(vert.y()>top-1e-9&&Math.abs(side-p.headWidth()/2)<1e-9)head=true;
                if(vert.y()<top-p.railHeight()+.026&&Math.abs(side-p.footWidth()/2)<1e-9)foot=true;
            }
        }
        if(!head||!foot)throw new AssertionError("Cut end face misses its head or foot band at "+boundary);
        double expectedArea=p.footWidth()*.025+.022*(p.railHeight()-.025-.036)+p.headWidth()*.036;
        if(Math.abs(area-expectedArea)>1e-8)throw new AssertionError("Cut end face fills an I-section notch or overlaps itself at "+boundary+": "+area+" != "+expectedArea);
        if(expected.sub(road.at(boundary)).length()<.5)throw new AssertionError("Cut frame collapsed onto the road centre line at "+boundary);
    }
}
