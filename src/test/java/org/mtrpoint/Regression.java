package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;
import java.nio.file.*;

public final class Regression {
    public static Track line(String id,String start,String end,V3 a,V3 b){var p=new ArrayList<V3>();for(int i=0;i<=100;i++)p.add(a.lerp(b,i/100D));return new Track(id,start,end,p);}
    public static List<Track> y(){var a=new ArrayList<V3>();var b=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.25,x=.008*z*z;a.add(new V3(-x,0,z));b.add(new V3(x,0,z));}return List.of(new Track("a","0,0,0","-7,0,30",a),new Track("b","0,0,0","7,0,30",b),line("in","0,0,-20","0,0,0",new V3(0,0,-20),new V3(0,0,0)));}
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        List<Track> rails=y();var points=Detector.find(rails);require(points.size()==1&&points.get(0).kind()==Junction.Kind.Y,"Y classification");
        Junction y=points.get(0);Mesh left=PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,0),right=PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,1);
        require(!left.quads.equals(right.quads),"Blade movement changes geometry");
        var moving=PointSettings.DEFAULT.flags(true,true);
        var frog0=PointMesh.build(y,moving,Profile.STANDARD,0).quads.stream().filter(q->q.part().equals("frog")).toList();
        var frog1=PointMesh.build(y,moving,Profile.STANDARD,1).quads.stream().filter(q->q.part().equals("frog")).toList();
        var frogHalf=PointMesh.build(y,moving,Profile.STANDARD,.5).quads.stream().filter(q->q.part().equals("frog")).toList();
        require(!frog0.equals(frog1)&&!frogHalf.equals(frog0)&&!frogHalf.equals(frog1),"Movable frog has an intermediate position");
        var top=left.quads.stream().filter(q->q.part().equals("sleeper")).findFirst().orElseThrow();
        V3 edge=top.b().sub(top.a()),diagonal=top.c().sub(top.a());
        require(edge.z()*diagonal.x()-edge.x()*diagonal.z()>0,"Sleeper upper face points upward for correct lighting");
        require(left.quads.stream().filter(q->q.part().equals("sleeper")).toList().equals(right.quads.stream().filter(q->q.part().equals("sleeper")).toList()),"Sleepers must not move with blades");
        var ids=left.quads.stream().filter(q->q.part().equals("sleeper")).map(Mesh.Quad::index).distinct().toList();require(ids.size()>10&&ids.size()<100,"One bearer family");
        Mesh edited=PointMesh.build(y,PointSettings.DEFAULT.sleeper(3,.15),Profile.STANDARD,0);
        require(!edited.quads.stream().filter(q->q.index()==3).toList().equals(left.quads.stream().filter(q->q.index()==3).toList()),"Individual tie adjustment");
        require(edited.quads.stream().filter(q->q.index()==4).toList().equals(left.quads.stream().filter(q->q.index()==4).toList()),"Other ties stable");
        Track crossA=line("c","-20,0,0","20,0,0",new V3(-20,0,0),new V3(20,0,0)),crossB=line("d","0,0,-20","0,0,20",new V3(0,0,-20),new V3(0,0,20));
        var crossings=Detector.find(List.of(crossA,crossB));require(crossings.size()==1&&crossings.get(0).kind()==Junction.Kind.DIAMOND,"Disconnected diamond");
        Track elevated=line("e","0,3,-20","0,3,20",new V3(0,3,-20),new V3(0,3,20));require(Detector.find(List.of(crossA,elevated)).isEmpty(),"Overpass is not a diamond");
        Track connected=line("f",crossA.startNode,"0,0,20",new V3(-20,0,0),new V3(0,0,20));require(Detector.find(List.of(crossA,connected)).stream().noneMatch(j->j.kind()==Junction.Kind.DIAMOND),"Shared endpoint excluded");
        Mesh diamond=PointMesh.build(crossings.get(0),PointSettings.DEFAULT,Profile.STANDARD,0);require(diamond.quads.stream().noneMatch(q->q.part().equals("blade")),"Diamond has no switching blades");
        for(Mesh mesh:List.of(left,right,diamond))for(var q:mesh.quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))require(Double.isFinite(v.x()+v.y()+v.z()),"Finite mesh");
        try{PointSettings.DEFAULT.with(2,Double.NaN);throw new AssertionError("NaN accepted");}catch(IllegalArgumentException expected){}
        try{PointSettings.DEFAULT.with(8,-1);throw new AssertionError("Negative gauge accepted");}catch(IllegalArgumentException expected){}
        require(rails.get(0).points.equals(y.a().points),"Source geometry unchanged");
        var parallel=PointSettings.DEFAULT.with(14,12).with(16,1);
        require(PointMesh.sleeperNormal(y,parallel,2,0,20).distance(PointMesh.sleeperNormal(y,parallel,18,0,20))<1e-9,"Parallel bearers keep one angle");
        var fan=parallel.with(15,-15).with(16,2);
        require(PointMesh.sleeperNormal(y,fan,0,0,20).distance(PointMesh.sleeperNormal(y,fan,20,0,20))>.1,"Fan end angle differs");
        require(PointMesh.sleeperNormal(y,fan,0,0,20).equals(PointMesh.sleeperNormal(y,fan.with(16,3),0,0,20)),"Smooth and linear share start angle");
        require(PointMesh.sleeperNormal(y,fan,20,0,20).equals(PointMesh.sleeperNormal(y,fan.with(16,3),20,0,20)),"Smooth and linear share end angle");
        var random=new Random(403);
        for(int n=0;n<100;n++){V3 query=new V3(random.nextDouble()*20-10,random.nextDouble(),random.nextDouble()*35);Track track=rails.get(0);double expected=Double.MAX_VALUE;
            for(int i=1;i<track.points.size();i++){V3 a=track.points.get(i-1),delta=track.points.get(i).sub(a);double t=Math.max(0,Math.min(1,query.sub(a).dot(delta)/delta.dot(delta)));expected=Math.min(expected,query.distance(a.add(delta.mul(t))));}
            require(Math.abs(query.distance(track.at(track.nearest(query)))-expected)<1e-8,"Spatial nearest query agrees with exhaustive scan");}
        var opposite=new ArrayList<Track>();opposite.add(rails.get(0));opposite.add(rails.get(1));
        for(int i=0;i<2;i++){Track t=rails.get(i);opposite.add(new Track("opposite"+i,t.startNode,"back"+i,t.points.stream().map(v->new V3(-v.x(),v.y(),-v.z())).toList()));}
        require(Detector.find(opposite).stream().filter(v->v.kind()==Junction.Kind.Y).count()==2,"Both Y sides at one four-arm node");
        var three=new ArrayList<>(List.of(rails.get(0),rails.get(1),line("middle","0,0,0","0,0,30",new V3(0,0,0),new V3(0,0,30))));
        var triple=Detector.find(three);require(triple.size()==1&&triple.get(0).kind()==Junction.Kind.THREE,"Three-way fan has one independent editor");
        Mesh t0=PointMesh.build(triple.get(0),PointSettings.DEFAULT,Profile.STANDARD,0),tm=PointMesh.build(triple.get(0),PointSettings.DEFAULT,Profile.STANDARD,.5),t1=PointMesh.build(triple.get(0),PointSettings.DEFAULT,Profile.STANDARD,1);
        require(t0.quads.size()==tm.quads.size()&&tm.quads.size()==t1.quads.size(),"Three-way animation topology");
        require(!t0.quads.equals(tm.quads)&&!tm.quads.equals(t1.quads),"Three distinct blade positions");
        checkThreeWayStockContinuity(triple.get(0),t0);
        for(double position:new double[]{0,.25,.5,.75,1})require(PointMesh.build(y,moving,Profile.STANDARD,position).quads.size()==PointMesh.build(y,moving,Profile.STANDARD,0).quads.size(),"Stable movable frog topology");
        for(double position:new double[]{0,1}){
            Mesh heart=PointMesh.build(y,moving,Profile.STANDARD,position);
            var frogFaces=heart.quads.stream().filter(q->q.part().equals("frog")).toList();var wingFaces=heart.quads.stream().filter(q->q.part().equals("wing")).toList();
            V3 tip=frogFaces.get(12).a().lerp(frogFaces.get(12).d(),.5);double contact=Double.MAX_VALUE;
            for(int k=12;k<wingFaces.size();k+=18){var q=wingFaces.get(k);contact=Math.min(contact,Math.min(segmentDistance(tip,q.a(),q.b()),segmentDistance(tip,q.d(),q.c())));}
            require(contact<.003,"Movable heart point touches selected wing edge: "+contact);
        }
        require(left.quads.stream().filter(q->q.part().equals("wing")).count()>20,"Continuous wing rail assembly exists");
        checkBladeContact(y);
        checkBladePlaning(y);
        checkNativeCutCap();
        checkSleeperEdits(y);
        checkGuardMerge(y);
        System.out.println("PASS: guard merge joins one physical rail only, keeps only the outer mouths and never bridges rails metres apart");
        checkGuardSelection(y,triple.get(0));
        System.out.println("PASS: every crossing and fan check rail is selectable, and a manual station reaches its baked steel");
        checkGuardCut();
        System.out.println("PASS: a crossing cuts its check rails at every height the two sections still overlap, and never cuts one that runs clear underneath");
        checkStockContinuity(y);
        int conflictSamples=checkCrossingCutCoverage("Y turnout",y)[0],diamondTagged=-1;
        for(double degrees:new double[]{20,90}){
            double angle=Math.toRadians(degrees);V3 da=new V3(0,0,1),db=new V3(Math.sin(angle),0,Math.cos(angle));
            Track ta=line("cover-a","a0","a1",da.mul(-35),da.mul(35)),tb=line("cover-b","b0","b1",db.mul(-35),db.mul(35));
            for(Junction crossing:Detector.find(List.of(ta,tb)))if(crossing.kind()==Junction.Kind.DIAMOND){int[] measured=checkCrossingCutCoverage("diamond "+degrees,crossing);conflictSamples+=measured[0];diamondTagged=measured[1];}
        }
        System.out.println("PASS: crossing cuts cover every per-layer section conflict ("+conflictSamples+" real conflict intervals; diamond views keep "+diamondTagged+" tagged running-rail faces, so their cuts guard the rail a neighbour view supplies)");
        for(int control=17;control<=23;control++)require(!PointMesh.build(y,PointSettings.DEFAULT.with(control,.1),Profile.STANDARD,0).quads.equals(left.quads),"Crossing control changes actual geometry: "+control);
        var ties=left.quads.stream().filter(q->q.part().equals("sleeper")&&q.index()==12).toList();
        for(Track branch:List.of(y.a(),y.b())){
            double d=12*PointSettings.DEFAULT.sleeperSpacing()+PointSettings.DEFAULT.sleeperSpacing()/2;
            V3 tangent=branch.tangent(branch.nearest(y.a().at(d).lerp(y.b().at(d),.5)));
            require(ties.stream().anyMatch(q->q.a().distance(q.b())>.5&&Math.abs(q.b().sub(q.a()).unit().dot(tangent))<.002),"Each V arm is perpendicular to its own branch");
        }
        require(PointMesh.build(y,PointSettings.DEFAULT.with(16,1),Profile.STANDARD,0).quads.stream().filter(q->q.part().equals("sleeper")&&q.index()==12).count()<ties.size(),"V and parallel patterns differ");
        checkFixedHeart();
        CurvedTurnoutRegression.run();
        DiamondRegression.run();
        ReportedGeometryRegression.run();
        AssemblyRegression.run();
        org.mtrpoint.client.PointRendererAssemblyRegression.run();
        org.mtrpoint.client.CheckRailCompletenessRegression.run();
        org.mtrpoint.client.RailCacheRegression.run();
        org.mtrpoint.client.MergedGuardCutRegression.run();
        System.out.println("PASS: a merged cross-junction check run keeps exactly the steel the legacy bake keeps where the crossing rail covers it");
        org.mtrpoint.client.MergedGuardCutRegression.runOffStyleGuard();
        System.out.println("PASS: a guard whose height matches no crossing style group is still cut by the crossing rail it crosses, and one running clear above it is not");
        org.mtrpoint.client.MergedGuardCutRegression.runOffStyleFixedY();
        System.out.println("PASS: a pooled fixed-Y guard is cut by the crossing rail of every style group of its component (133 of 221 shared-road samples, exactly the fallback bake's cut), and one running clear above it is not");
        FollowupRegression.run();
        require(PointSettings.DEFAULT.lengthScale()==.9,"Default coverage multiplier");
        for(double last:new double[]{3.19,8.05,12.87}){var rows=PointMesh.sleeperDistances(last,.6);require(Math.abs(rows.get(rows.size()-1)-last)<1e-9,"Last sleeper follows native phase");for(int i=1;i<rows.size();i++)require(rows.get(i)-rows.get(i-1)<=.60000001,"No long gap before final sleeper");}
        checkScissors(false);checkScissors(true);
        Files.createDirectories(Path.of("build/previews"));export(PointMesh.build(y,moving,Profile.STANDARD,0),"y-movable");
export(t0,"three-left");export(tm,"three-center");export(t1,"three-right");
        export(left,"y-left");export(right,"y-right");export(diamond,"diamond");
        System.out.println("PASS: Y/diamond detection, overpass exclusion, animation, bearer edits, immutable inputs and parameter validation");
    }
    public static List<Track> scissors(boolean curved){
        var roads=new ArrayList<Track>();String[] starts={"sw","se","sw","se"},ends={"nw","ne","ne","nw"};
        for(int road=0;road<4;road++){var points=new ArrayList<V3>();for(int i=0;i<=160;i++){double t=i/160D,blend=t*t*(3-2*t),bend=curved?4*Math.sin(Math.PI*t):0;double x=road==0?-3:road==1?3:road==2?-3+6*blend:3-6*blend;points.add(new V3(x+bend,0,-20+40*t));}roads.add(new Track("sc"+road,starts[road],ends[road],points));}return roads;
    }
    private static void checkScissors(boolean curved)throws Exception{
        var roads=scissors(curved);var junctions=Detector.find(roads);var groups=ScissorsLayout.find(junctions);
        require(groups.size()==1,"Recognize four-node scissors with curved="+curved);var group=groups.get(0);
        require(group.turnouts().size()==4&&group.tracks().size()==4,"Four independent turnout editors and all roads retained");
        Mesh assembled=group.centerMesh(PointSettings.DEFAULT,Profile.STANDARD);
        Mesh crossing=new Mesh();crossing.quads.addAll(assembled.quads);
        for(var q:assembled.quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){double d=v.sub(group.crossing().center()).dot(group.axis());require(d>=group.lo()-1e-7&&d<=group.hi()+1e-7,"Central mesh stays in its shared region");}
        for(var y:group.turnouts()){
            Mesh a=group.clip(PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,0,group.boundary(y,PointSettings.DEFAULT)),y,PointSettings.DEFAULT),b=group.clip(PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,1,group.boundary(y,PointSettings.DEFAULT)),y,PointSettings.DEFAULT);
            require(a.quads.size()==b.quads.size(),"Scissors clipping preserves blade animation topology");assembled.quads.addAll(a.quads);
        }
        DiamondRegression.scissors(group,crossing,assembled);
        export(assembled,curved?"scissors-curved":"scissors");
    }
    private static void checkFixedHeart(){
        var a=line("fixed-a","node","left",new V3(0,0,0),new V3(-4,0,20));
        var b=line("fixed-b","node","right",new V3(0,0,0),new V3(4,0,20));
        var junction=Detector.find(List.of(a,b)).get(0);var profile=Profile.STANDARD;
        Mesh mesh=PointMesh.build(junction,PointSettings.DEFAULT,profile,0);
        double slope=.2,cos=1/Math.sqrt(1+slope*slope),half=profile.headWidth()/(2*cos);
        double crossZ=profile.centerOffset()/(cos*slope);
        for(double ratio:new double[]{-.75,-.25,.25,.75,1.25,2,3}){
            var spans=topSpans(mesh,crossZ+ratio*half/slope,profile.top(),"frog");
            if(ratio<1){
                require(spans.size()==1,"Fixed frog nose must be solid after the two heads meet");
                require(Math.abs(spans.get(0)[1]-spans.get(0)[0]-2*half*(1+ratio))<1e-6,"Solid nose follows the V edges");
            }else{
                require(spans.size()==2,"Two separate heart rails before joining");
                for(double[] span:spans)require(Math.abs(span[1]-span[0]-2*half)<1e-6,"Heart rails retain full head width up to joining");
            }
        }
        for(double advance:new double[]{.3,.6,1}){
            var heart=topSpans(mesh,crossZ+advance,profile.top(),"frog");var wings=topSpans(mesh,crossZ+advance,profile.top(),"wing");
            require(heart.size()==2&&wings.size()==2,"Working wings run along both heart rails");
            double expected=PointSettings.DEFAULT.flangeway()/cos;
            require(Math.abs(heart.get(0)[0]-wings.get(0)[1]-expected)<1e-6&&Math.abs(wings.get(1)[0]-heart.get(1)[1]-expected)<1e-6,"Wing flangeway stays constant until the terminal flare");
        }
    }
    /** A cut face on a native rail model closes with the model's own section and its own material.
     * The outline below is a chamfered I-beam: its area differs from the built-in three-part
     * fallback, so the face cannot be the fallback rectangle stack, and the faces that form the
     * outline carry a texture the mod's plain steel does not have. */
    private static void checkNativeCutCap(){
        Profile.Surface face=new Profile.Surface("mtrpoint:test/native_rail.png",.1f,.2f,.3f,.4f,0xff203040);
        Profile.Surface other=new Profile.Surface("mtrpoint:test/native_other.png",.1f,.2f,.3f,.4f,0xff405060);
        double[][] outline={{-.07,0},{.07,0},{.07,.02},{.05,.025},{.011,.025},{.011,.129},{.034,.129},{.034,.155},{.026,.165},{-.026,.165},{-.034,.155},{-.034,.129},{-.011,.129},{-.011,.025},{-.07,.025}};
        var faces=new ArrayList<Mesh.Quad>();
        for(int i=0;i<outline.length;i++){
            double[] from=outline[i],to=outline[(i+1)%outline.length];
            // Nine of the fifteen long faces carry the first material, so the modal surface of the
            // outline is well defined rather than a tie between two of them.
            faces.add(new Mesh.Quad(new V3(from[0],from[1],0),new V3(to[0],to[1],0),new V3(to[0],to[1],1),new V3(from[0],from[1],1),i<9?face:other,"rail",-1));
        }
        double area=0;for(int i=0;i<outline.length;i++){double[] a=outline[i],b=outline[(i+1)%outline.length];area+=a[0]*b[1]-b[0]*a[1];}
        area=Math.abs(area)/2;
        ModelDetail detail=new ModelDetail(faces,List.of(),List.of(),0,.165,.068,0,1,.5,0,false);
        Profile nativeProfile=new Profile(1.435,.264,.068,.14,.165,Profile.STEEL,Profile.TIMBER,"native-cut",false,detail);
        PointSettings s=PointSettings.DEFAULT;
        V3 center=new V3(4,0,5),tangent=new V3(0,0,1);
        Mesh start=new Mesh();start.railCutCap(center,tangent,nativeProfile,s,true);
        Mesh end=new Mesh();end.railCutCap(center,tangent,nativeProfile,s,false);
        require(!start.quads.isEmpty()&&!end.quads.isEmpty(),"A native rail cut emits an end face at both cuts");
        double measured=0;
        for(var q:start.quads){
            require(q.surface().texture().equals(face.texture()),"A cut face on a native rail must use the model's own surface, not "+q.surface().texture());
            V3 u=q.b().sub(q.a()),v=q.c().sub(q.a());
            measured+=Math.sqrt(Math.pow(u.y()*v.z()-u.z()*v.y(),2)+Math.pow(u.z()*v.x()-u.x()*v.z(),2)+Math.pow(u.x()*v.y()-u.y()*v.x(),2))/2;
            // The cap frame's x runs along the tangent's lateral, which is the same axis the swept
            // rail faces use, so the profile coordinate is recovered by projecting back onto it.
            for(V3 vertex:List.of(q.a(),q.b(),q.c()))require(onOutline(outline,center.x()-vertex.x(),vertex.y()-(nativeProfile.top()-detail.railTop())),"A native cut face must follow the model's real profile outline at "+(center.x()-vertex.x())+","+(vertex.y()-(nativeProfile.top()-detail.railTop())));
            require(Math.abs(capNormal(q).y())<.02,"A cut face is a vertical cross-section of the rail");
        }
        require(Math.abs(measured-area)<1e-9,"A native cut face must close the whole real section: "+measured+" vs "+area);
        require(start.quads.stream().allMatch(q->capNormal(q).dot(tangent)<-.999),"The start cut face looks out of the removed steel");
        require(end.quads.stream().allMatch(q->capNormal(q).dot(tangent)>.999),"The end cut face looks out of the removed steel");
        // A profile without a native model keeps the built-in three-part fallback and its steel.
        Mesh fallback=new Mesh();fallback.railCutCap(center,tangent,Profile.STANDARD,s,true);
        require(!fallback.quads.isEmpty()&&fallback.quads.stream().allMatch(q->q.surface().equals(Profile.STEEL)),"A profile without a native model keeps the built-in steel end face");
        System.out.println("PASS: a rail cut closes with the native model's own profile outline and surface, and the built-in fallback keeps its steel");
    }
    private static boolean onOutline(double[][] outline,double x,double y){
        for(double[] point:outline)if(Math.abs(point[0]-x)<1e-9&&Math.abs(point[1]-y)<1e-9)return true;
        return false;
    }
    private static V3 capNormal(Mesh.Quad q){
        V3 u=q.b().sub(q.a()),v=q.c().sub(q.a());
        return new V3(u.y()*v.z()-u.z()*v.y(),u.z()*v.x()-u.x()*v.z(),u.x()*v.y()-u.y()*v.x()).unit();
    }
    /** A switch blade keeps its native section. A blade is swept whole and, while it still meets
     * its stock rail, that whole section is planed by clipping it from the stock-rail side with the
     * vertical planing surface at the blade's own cut point; a blade that carries no cut point is
     * the untouched section. The planed mesh is compared with an independently assembled clip of the
     * same section, so a taper that shrinks the section instead of cutting it fails here. */
    private static void checkBladePlaning(Junction j){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        double extent=PointMesh.extent(j,s),start=TurnoutFrame.start(j,extent),side=TurnoutFrame.side(j,extent);
        FrogGeometry frog=new FrogGeometry(j,s,p,extent);
        double length=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min(((frog.sa+frog.sb)/2-start)*.65,9));
        int planed=0,clear=0;
        for(double d=start;d+.24<=start+length;d+=.24){
            var cutA=TurnoutFrame.blade(j.a(),j.b(),d,side,p.centerOffset(),p.headWidth(),0,start,length,s);
            var cutB=TurnoutFrame.blade(j.a(),j.b(),d+.24,side,p.centerOffset(),p.headWidth(),0,start,length,s);
            Mesh plain=new Mesh();plain.rail(cutA.point(),cutB.point(),1,1,p,s,"blade");
            Mesh built=new Mesh();built.blade(cutA.point(),cutB.point(),cutA.cut(),cutB.cut(),p,s);
            if(cutA.cut()==null&&cutB.cut()==null){
                clear++;
                require(built.quads.equals(plain.quads),"An unplaned blade keeps its full native section");
                continue;
            }
            planed++;
            require(built.quads.equals(planedSection(cutA,cutB,plain).quads),"A planed blade is the full native section clipped from the stock-rail side, never a shrunken section");
            require(built.quads.size()==plain.quads.size()*2,"A planed blade keeps every face of the clipped section: "+built.quads.size()+" vs "+plain.quads.size());
            require(built.quads.stream().anyMatch(Regression::collapsed),"A planed blade is cut open, not scaled down");
            for(var q:built.quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))
                require(v.sub(cutA.cut()).dot(planingNormal(cutA,cutB))<=1e-9,"A planed blade never keeps steel behind its planing surface");
        }
        require(planed>0&&clear>0,"The fixture must contain both planed and unplaned blade cells: "+planed+" planed, "+clear+" clear");
        for(double position:new double[]{0,1}){
            var quads=PointMesh.build(j,s,p,position).quads.stream().filter(q->q.part().equals("blade")).toList();
            require(!quads.isEmpty(),"The built turnout draws its switch blades");
            require(quads.stream().allMatch(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Double.isFinite(v.x()+v.y()+v.z()))),"Planed blade mesh is finite");
        }
        System.out.println("PASS: a switch blade is planed by clipping its full native section from the stock-rail side, never tapered, and a blade clear of its stock rail keeps its full section");
    }
    /** The restored planing rebuilt from the cut points alone: the whole native section cut by the
     * vertical plane through the blade's planing station, on the side the blade is kept. */
    private static Mesh planedSection(TurnoutFrame.Blade cutA,TurnoutFrame.Blade cutB,Mesh plain){
        V3 origin=cutA.cut()!=null?cutA.cut():cutB.cut(),normal=planingNormal(cutA,cutB);
        return Mesh.clipAnimated(plain,origin,normal);
    }
    /** The plane the restored planing uses, with no access to the private implementation state. */
    private static V3 planingNormal(TurnoutFrame.Blade cutA,TurnoutFrame.Blade cutB){
        V3 origin=cutA.cut()!=null?cutA.cut():cutB.cut(),along=cutA.cut()!=null&&cutB.cut()!=null?cutB.cut().sub(cutA.cut()):cutB.point().sub(cutA.point()),normal=along.lateral();
        V3 away=cutA.point().sub(cutA.cut()!=null?cutA.cut():origin).add(cutB.point().sub(cutB.cut()!=null?cutB.cut():origin));
        return away.dot(normal)>0?normal.mul(-1):normal;
    }
    /** True when a quad lost its area, which is what clipping a swept section leaves behind. */
    private static boolean collapsed(Mesh.Quad q){
        var distinct=new ArrayList<V3>();
        for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(distinct.stream().noneMatch(other->other.distance(v)<1e-9))distinct.add(v);
        if(distinct.size()<3)return true;
        V3 u=distinct.get(1).sub(distinct.get(0)),v=distinct.get(2).sub(distinct.get(0));
        return Math.sqrt(Math.pow(u.y()*v.z()-u.z()*v.y(),2)+Math.pow(u.z()*v.x()-u.x()*v.z(),2)+Math.pow(u.x()*v.y()-u.y()*v.x(),2))<1e-12;
    }
    private static void checkBladeContact(Junction j){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;double extent=PointMesh.extent(j,s),start=TurnoutFrame.start(j,extent),side=TurnoutFrame.side(j,extent);
        FrogGeometry frog=new FrogGeometry(j,s,p,extent);double length=s.bladeLength()>0?s.bladeLength():Math.max(2,Math.min(((frog.sa+frog.sb)/2-start)*.65,9));
        int planed=0,full=0;boolean restored=false;
        for(double d=start;d<=start+length;d+=.05){
            var blade=TurnoutFrame.blade(j.a(),j.b(),d,side,p.centerOffset(),p.headWidth(),0,start,length,s);double stockAt=j.b().nearest(j.a().at(d));
            V3 stock=j.b().at(stockAt).add(j.b().tangent(stockAt).lateral().mul(side*p.centerOffset()));double separation=new V3(blade.point().x()-stock.x(),0,blade.point().z()-stock.z()).length();
            if(blade.cut()!=null){planed++;require(separation<p.headWidth()+1e-4,"Blade planing continues outside stock contact");}
            else {full++;restored=true;}
            if(restored)require(blade.cut()==null,"Blade is planed again after reaching its full section");
        }
        require(planed>0&&full>planed,"Only the short stock-contact tip may be planed");
        Mesh mesh=PointMesh.build(j,s,p,0);require(mesh.quads.stream().filter(q->q.part().equals("blade")).allMatch(q->List.of(q.a(),q.b(),q.c(),q.d()).stream().allMatch(v->Double.isFinite(v.x()+v.y()+v.z()))),"Planed blade mesh is finite");
    }
    private static void checkSleeperEdits(Junction j){
        Profile p=Profile.STANDARD;PointSettings base=PointSettings.DEFAULT.with(16,0);
        var path0=PointMesh.build(j,base.sleeperPath(0),p,0).quads.stream().filter(q->q.part().equals("sleeper")).toList();
        var path1=PointMesh.build(j,base.sleeperPath(1),p,0).quads.stream().filter(q->q.part().equals("sleeper")).toList();
        require(!path0.equals(path1),"Sleeper curve reference path changes bearer direction");
        PointSettings added=base.sleeperPath(1).addSleeper(1,5.25);Mesh withAdded=PointMesh.build(j,added,p,0);
        require(withAdded.quads.stream().anyMatch(q->q.index()>=256&&q.part().equals("sleeper")),"Manual sleeper is generated on the chosen path");
        PointSettings deleted=added.removeSleeper(3);Mesh without=PointMesh.build(j,deleted,p,0);
        require(without.quads.stream().noneMatch(q->q.index()==3&&(q.part().equals("sleeper")||q.part().equals("fastener"))),"Deleted automatic sleeper is absent");
        PointSettings v=base.with(16,4),split=v.toggleSleeper(12,PointSettings.SLEEPER_SPLIT);
        var joinedFaces=PointMesh.build(j,v,p,0).quads.stream().filter(q->q.index()==12&&q.part().equals("sleeper")).toList();
        var splitFaces=PointMesh.build(j,split,p,0).quads.stream().filter(q->q.index()==12&&q.part().equals("sleeper")).toList();
        require(!joinedFaces.equals(splitFaces),"Split replaces a joined V bearer with separate ordinary sleepers");
        double ordinary=2*(p.centerOffset()+base.sleeperOverhang())+.02;
        require(splitFaces.stream().flatMap(q->List.of(q.a().distance(q.b()),q.b().distance(q.c()),q.c().distance(q.d()),q.d().distance(q.a())).stream()).allMatch(length->length<=ordinary),"Split bearer arms do not bridge between routes");
        PointSettings changed=base.with(4,.83).with(5,.31).with(6,.18).with(7,.61).with(14,12).with(15,-9).with(16,3).with(17,.2).flags(true,false).style("custom").sleeperPath(1).sleeper(2,.1).toggleSleeper(4,PointSettings.SLEEPER_FULL).addSleeper(1,6);
        PointSettings reset=changed.resetSleepers();
        require(reset.sleeperSpacing()==PointSettings.DEFAULT.sleeperSpacing()&&reset.sleeperWidth()==PointSettings.DEFAULT.sleeperWidth()&&reset.sleeperHeight()==PointSettings.DEFAULT.sleeperHeight()&&reset.sleeperOverhang()==PointSettings.DEFAULT.sleeperOverhang(),"Sleeper reset restores dimensions");
        require(reset.sleeperAngle()==0&&reset.sleeperEndAngle()==0&&reset.sleeperMode()==PointSettings.DEFAULT.sleeperMode()&&reset.sleeperPath()==0&&reset.sleeperShifts().isEmpty()&&reset.sleeperOverrides().isEmpty()&&reset.addedSleepers().isEmpty(),"Sleeper reset clears arrangement and individual edits");
        require(reset.guardShift()==changed.guardShift()&&reset.movableFrog()==changed.movableFrog()&&reset.enabled()==changed.enabled()&&reset.profileStyle().equals(changed.profileStyle()),"Sleeper reset preserves non-sleeper settings");
        var automatic=GuardRails.forJunction(j,base,p);require(!automatic.isEmpty(),"Turnout exposes editable guards");var run=automatic.get(0);
        PointSettings guarded=base.guard(0,new PointSettings.GuardEdit(run.start()+.1,run.end()-.1,false,true,"manual-test"));
        var edited=GuardRails.forJunction(j,guarded,p).get(0);require(Math.abs(edited.start()-run.start()-.1)<1e-8&&Math.abs(edited.end()-run.end()+.1)<1e-8&&!edited.flareStart()&&edited.flareEnd()&&edited.mergeGroup().equals("manual-test"),"Manual guard stations and merge group are applied");
        PointSettings decoded=org.mtrpoint.AppearanceData.decode(org.mtrpoint.AppearanceData.JSON.toJson(guarded));require(decoded.guardEdits().equals(guarded.guardEdits()),"Manual guard edits survive persistence");
        require(guarded.resetSleepers().guardEdits().equals(guarded.guardEdits()),"Sleeper reset preserves manual guard edits");
    }
    /** One merge group is one drawn rail: merge() itself joins only runs that really are the same
     * check rail on the same side of a track, and it must keep only the outer mouths of the union.
     * The editor records any selection and only reports whether it became one rail, so the
     * geometric predicate below is advisory rather than a refusal. */
    private static void checkGuardMerge(Junction y){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        var runs=GuardRails.forJunction(y,s,p);require(runs.size()==2,"A Y turnout exposes two outer check runs");
        require(!GuardRails.mergeable(runs.get(0),runs.get(1)),"The two outer guards of a Y are not one rail");
        require(GuardRails.merge(runs).size()==2,"Unmergeable guards stay separate");
        require(GuardRails.exposeEnds(GuardRails.merge(runs)).stream().allMatch(r->r.flareStart()&&r.flareEnd()),"Separate guards keep both exposed mouths");
        Track road=line("guard-road","0,0,0","0,0,30",new V3(0,0,0),new V3(0,0,30));
        Track nearby=line("guard-road-b","0,0,0","0,0,30",new V3(.02,0,0),new V3(.02,0,30));
        require(GuardRails.mergeable(new GuardRails.Run(road,6,10,.6,true,true,p,s,"g"),new GuardRails.Run(road,11.2,15,.6,true,true,p,s,"g")),"Two collinear runs on one track are mergeable");
        // The editor widens both picks to the union before saving, so this is the stored group.
        var grouped=List.of(new GuardRails.Run(road,6,15,.6,true,true,p,s,"g"),new GuardRails.Run(road,6,15,.6,true,true,p,s,"g"));
        require(GuardRails.merge(grouped).size()==1,"One manual group draws one rail");
        var union=GuardRails.exposeEnds(GuardRails.merge(grouped)).get(0);
        require(Math.abs(union.start()-6)<1e-8&&Math.abs(union.end()-15)<1e-8&&union.flareStart()&&union.flareEnd(),"A merged rail keeps only its outer mouths");
        var sides=List.of(new GuardRails.Run(road,6,10,.6,true,true,p,s,"g"),new GuardRails.Run(road,6,10,-.6,true,true,p,s,"g"));
        require(!GuardRails.mergeable(sides.get(0),sides.get(1)),"Opposite check rails of one track are not one rail");
        require(GuardRails.merge(sides).size()==2,"A group never fuses both sides of one track");
        var shortRun=new GuardRails.Run(road,6,10,.6,true,true,p,s,"g");
        var longRun=new GuardRails.Run(nearby,5,12,.6,true,true,p,s,"g");
        require(GuardRails.mergeable(shortRun,longRun),"One rail sampled on two coincident tracks is mergeable");
        require(GuardRails.merge(List.of(shortRun,longRun)).size()==1,"A manual group merges a short run into a longer one");
        require(GuardRails.merge(List.of(longRun,shortRun)).size()==1,"A manual group merge does not depend on input order");
        var apart=List.of(new GuardRails.Run(road,2,4,.6,true,true,p,s,"g"),new GuardRails.Run(nearby,20,22,.6,true,true,p,s,"g"));
        require(!GuardRails.mergeable(apart.get(0),apart.get(1)),"Two collinear rails metres apart are not one rail");
        require(GuardRails.merge(apart).size()==2,"A group never bridges two rails metres apart");
        // The editor matches a selection back through this provenance. A proximity match lit up
        // every rail that happened to run inside the merge tolerance of the clicked one, which is
        // exactly what a shallow crossing looks like, so a click selected several directions at once.
        var owners=new ArrayList<Set<Integer>>();
        require(GuardRails.merge(grouped,owners).size()==1&&owners.equals(List.of(Set.of(0,1))),"A merged rail reports every run it was built from");
        owners.clear();
        require(GuardRails.merge(sides,owners).size()==2&&owners.equals(List.of(Set.of(0),Set.of(1))),"Unmerged rails keep their own selection identity");
        // One physical rail sampled from two tracks whose node order is opposite: both ends of the
        // overlap are interior, so neither may keep an open mouth.
        Track backwards=line("guard-road-back","0,0,30","0,0,0",new V3(0,0,30),new V3(0,0,0));
        var mirrored=List.of(new GuardRails.Run(road,6,10,.6,true,true,p,s,""),new GuardRails.Run(backwards,20,24,-.6,true,true,p,s,""));
        require(GuardRails.exposeEnds(mirrored).stream().noneMatch(r->r.flareStart()||r.flareEnd()),"One rail seen from opposite track directions keeps no interior mouth");
    }
    /** A check rail must be interrupted by the rail that crosses it. The detector accepts two
     * crossing roads up to 80 mm apart in height, but the cut used to be skipped for any guard more
     * than 5 mm off the other rail's level, so such a crossing baked its guard steel straight
     * through the running rail. */
    private static void checkGuardCut(){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;double reach=20;
        Track a=line("cut-a","a0","a1",new V3(-reach,0,0),new V3(reach,0,0));
        Track flat=line("cut-flat","b0","b1",new V3(0,0,-reach),new V3(0,0,reach));
        Track tilted=line("cut-tilt","b0","b1",new V3(0,.075,-reach),new V3(0,.075,reach));
        var detected=Detector.find(List.of(a,tilted));
        require(detected.size()==1&&detected.get(0).kind()==Junction.Kind.DIAMOND,"Two crossing roads 75 mm apart in height are still one crossing");
        double extent=PointMesh.extent(detected.get(0),s);
        Junction level=crossing("cut-level",a,flat,reach,extent);
        Junction offset=crossing("cut-offset",a,tilted,reach,extent);
        var levelFaces=guardFaces(assembly(level,s,p));
        require(!levelFaces.isEmpty(),"A crossing bakes check rails");
        double levelArea=guardArea(levelFaces),offsetArea=guardArea(guardFaces(assembly(offset,s,p)));
        Track lifted=line("cut-lift","b0","b1",new V3(0,.4,-reach),new V3(0,.4,reach));
        double liftedArea=guardArea(guardFaces(assembly(crossing("cut-lift",a,lifted,reach,extent),s,p)));
        require(levelArea<liftedArea-1e-6,"A crossing cuts its check rails: "+levelArea+" vs "+liftedArea+" m2");
        require(offsetArea<liftedArea-1e-6,"A check rail is cut by the crossing rail at any height the sections still overlap: "+offsetArea+" vs "+liftedArea+" m2");
        // A rail a whole section higher cannot interfere: the guard below it survives exactly as an
        // uncut band, which is the same steel the lifted assembly keeps.
        require(Math.abs(liftedArea-levelArea)>1e-6,"A rail a whole section higher runs clear over the guard");
    }
    private static Mesh assembly(Junction j,PointSettings s,Profile p){
        // The pooled path, which the world uses as soon as a neighbouring turnout pools a guard:
        // a crossing's own check rails are then baked as guard steel instead of frog wings, and
        // that is the path whose 5 mm gate used to skip the cut.
        return DiamondGeometry.combine(List.of(new DiamondGeometry.Request(j,s,p.tune(s),PointMesh.extent(j,s))),GuardRails.crossing(j,s,p.tune(s)));
    }
    private static Junction crossing(String id,Track a,Track b,double station,double extent){return new Junction(id,Junction.Kind.DIAMOND,a,b,new V3(0,0,0),station,station,extent);}
    private static List<Mesh.Quad> guardFaces(Mesh mesh){return mesh.quads.stream().filter(q->q.part().equals("guard")).toList();}
    /** Horizontal footprint of the check steel: a cut removes top faces, so the sum shrinks. */
    private static double guardArea(List<Mesh.Quad> faces){
        double area=0;for(var q:faces)area+=Math.abs(V3.crossXZ(q.c().sub(q.a()),q.d().sub(q.b())))*.5;
        return area;
    }
    /** Every check rail the world draws must be selectable, and a manual station on it must reach
     * the baked steel. A plain crossing and a three-way fan own their check rails inside a shared
     * assembly, so an editor that only enumerated the frog guards showed those rails while they
     * could never be selected or merged: half of a multi-source guard had no partner to group with,
     * and the merge silently changed nothing. The stock rail beside a switch blade stays excluded. */
    private static void checkGuardSelection(Junction y,Junction fan){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        var turnout=GuardRails.selectable(y,s,p,null,PointMesh.YBoundary.nominal(y,s));
        require(turnout.size()>=2,"A turnout exposes its check rails");
        require(turnout.stream().noneMatch(GuardRails::isBladeAdjacent),"The stock rail beside a blade is never selectable");
        var boundary=PointMesh.YBoundary.nominal(fan,s);
        var runs=GuardRails.selectable(fan,s,p,null,boundary);
        require(!runs.isEmpty(),"A three-way fan exposes its check rails");
        require(runs.stream().noneMatch(GuardRails::isBladeAdjacent),"A fan never selects steel beside a blade");
        require(moved(fan,s,s.guard(0,guardEdit(runs.get(0))),p,boundary),"A manual station on a fan check rail reaches its baked assembly");
        for(double degrees:new double[]{20,90}){
            double angle=Math.toRadians(degrees);V3 da=new V3(0,0,1),db=new V3(Math.sin(angle),0,Math.cos(angle));
            Track ta=line("select-a","a0","a1",da.mul(-35),da.mul(35)),tb=line("select-b","b0","b1",db.mul(-35),db.mul(35));
            for(Junction crossing:Detector.find(List.of(ta,tb)))if(crossing.kind()==Junction.Kind.DIAMOND){
                var crossBoundary=PointMesh.YBoundary.nominal(crossing,s);
                var crossRuns=GuardRails.selectable(crossing,s,p,null,crossBoundary);
                require(!crossRuns.isEmpty(),"A crossing exposes its check rails at "+degrees+" degrees");
                require(moved(crossing,s,s.guard(0,guardEdit(crossRuns.get(0))),p,crossBoundary),"A manual station on a crossing check rail reaches the shared assembly at "+degrees+" degrees");
            }
        }
    }
    private static PointSettings.GuardEdit guardEdit(GuardRails.Run run){return new PointSettings.GuardEdit(run.start()+.4,Math.max(run.start()+.5,run.end()-.4),false,false,"manual-select");}
    private static boolean moved(Junction j,PointSettings plain,PointSettings edited,Profile p,PointMesh.YBoundary boundary){
        return baked(j,plain,p,boundary)!=baked(j,edited,p,boundary);
    }
    private static int baked(Junction j,PointSettings s,Profile p,PointMesh.YBoundary boundary){
        Mesh mesh=j.kind()==Junction.Kind.DIAMOND?DiamondGeometry.combine(List.of(new DiamondGeometry.Request(j,s,p.tune(s),PointMesh.extent(j,s)))):PointMesh.build(j,s,p,0,boundary);
        return mesh.quads.hashCode();
    }
    /** No crossing may keep steel where two rail sections really overlap in the same layer: every
     * per-layer conflict of a crossing rail pair must fall inside a cut interval, otherwise a frog
     * gap or flange channel stays closed. RailSection measures the real intersection of the two
     * swept sections, so this is the contract that decides whether a crossing is actually cut.
     * Only opposite-side pairs of a node-sharing turnout are crossings: the two branches' same-side
     * rails are one continuous outer rail that merely grazes at the node and must stay whole. */
    private static int[] checkCrossingCutCoverage(String label,Junction j){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;double extent=PointMesh.extent(j,s),radius=p.centerOffset();
        var roads=j.tracks();var cuts=RailCuts.forJunction(j,s,p);var layers=RailSection.of(p,s);int checked=0;
        // A crossing layout builds its own steel: its view carries no tagged running-rail face for a
        // cut to land on, so the intervals measured below only guard the rail a cut really removes.
        int tagged=j.kind()==Junction.Kind.DIAMOND?(int)PointMesh.build(j,s,p,0).quads.stream().filter(q->q.rail()!=null).count():-1;
        for(Track road:roads){
            double centre=road.nearest(j.center()),lo=Math.max(0,centre-2*extent),hi=Math.min(road.length,centre+2*extent);
            for(int sign:new int[]{-1,1}){
                double offset=sign*radius;
                var holes=cuts.stream().filter(c->c.road().id.equals(road.id)&&Math.abs(c.offset()-offset)<1e-6).map(c->new double[]{c.start(),c.end()}).toList();
                for(Track cutter:roads){if(cutter==road)continue;
                    double cutterCentre=cutter.nearest(j.center()),cutterLo=Math.max(0,cutterCentre-2*extent),cutterHi=Math.min(cutter.length,cutterCentre+2*extent);
                    for(int side:new int[]{-1,1}){
                        if(j.kind()!=Junction.Kind.DIAMOND&&side==sign)continue;
                        for(RailSection.Layer layer:layers)
                            for(double[] conflict:RailSection.conflicts(road,offset,layer,layers,cutter,side*radius,cutterLo,cutterHi,lo,hi)){
                                checked++;
                                require(holes.stream().anyMatch(h->conflict[0]>=h[0]-1e-6&&conflict[1]<=h[1]+1e-6),label+" leaves uncut steel at the crossing of "+road.id+" offset "+String.format(Locale.ROOT,"%.4f",offset)+" over ["+String.format(Locale.ROOT,"%.4f",conflict[0])+","+String.format(Locale.ROOT,"%.4f",conflict[1])+"]");
                            }
                    }
                }
            }
        }
        return new int[]{checked,tagged};
    }
    private static void checkStockContinuity(Junction j){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;Mesh mesh=PointMesh.build(j,s,p,0);var tops=DiamondRegression.tops(mesh,p.top());double extent=PointMesh.extent(j,s),start=TurnoutFrame.start(j,extent),side=TurnoutFrame.side(j,extent),second=j.b().nearest(j.a().at(start));
        for(double delta:new double[]{-.02,0,.02}){Track road=delta<=0?j.a():j.b();double at=delta<=0?Math.max(0,start+delta):Math.min(j.b().length,second+delta);V3 point=road.at(at).add(road.tangent(at).lateral().mul(side*p.centerOffset()));require(DiamondRegression.coverage(tops,point)>0,"Outer stock rail is open at the blade toe");}
    }
    private static void checkThreeWayStockContinuity(Junction j,Mesh mesh){
        Profile p=Profile.STANDARD;var tops=DiamondRegression.tops(mesh,p.top());double start=TurnoutFrame.start(j,PointMesh.extent(j,PointSettings.DEFAULT));Track common=j.tracks().get(0),outer=j.tracks().get(2);double second=outer.nearest(common.at(start));
        for(int sign:new int[]{-1,1})for(double delta:new double[]{-.02,0,.02}){Track road=delta<=0?common:outer;double at=delta<=0?Math.max(0,start+delta):Math.min(outer.length,second+delta);V3 point=road.at(at).add(road.tangent(at).lateral().mul(sign*p.centerOffset()));require(DiamondRegression.coverage(tops,point)>0,"Three-way outer stock rail is open at the blade toe");}
    }
    private static List<double[]> topSpans(Mesh mesh,double z,double top,String part){
        var spans=new ArrayList<double[]>();
        for(var q:mesh.quads){
            if(!q.part().equals(part))continue;var vertices=List.of(q.a(),q.b(),q.c(),q.d());
            if(vertices.stream().anyMatch(v->Math.abs(v.y()-top)>1e-8))continue;
            double lo=Double.POSITIVE_INFINITY,hi=Double.NEGATIVE_INFINITY;
            for(int i=0;i<4;i++){V3 a=vertices.get(i),b=vertices.get((i+1)%4);if((a.z()-z)*(b.z()-z)<=0&&Math.abs(a.z()-b.z())>1e-9){double x=a.lerp(b,(z-a.z())/(b.z()-a.z())).x();lo=Math.min(lo,x);hi=Math.max(hi,x);}}
            if(hi>lo+1e-9)spans.add(new double[]{lo,hi});
        }
        spans.sort(Comparator.comparingDouble(v->v[0]));var result=new ArrayList<double[]>();
        for(double[] span:spans){if(result.isEmpty()||span[0]>result.get(result.size()-1)[1]+1e-8)result.add(span);else result.get(result.size()-1)[1]=Math.max(result.get(result.size()-1)[1],span[1]);}
        return result;
    }
    private static double segmentDistance(V3 p,V3 a,V3 b){V3 d=b.sub(a);return p.distance(a.add(d.mul(Math.max(0,Math.min(1,p.sub(a).dot(d)/d.dot(d))))));}
    public static void export(Mesh mesh,String name)throws Exception{
        var out=new StringBuilder("# MTR Point procedural mesh / metres\n");int index=1;String last="";
        for(var q:mesh.quads){if(!last.equals(q.part())){last=q.part();out.append("g ").append(last).append('\n');}for(var v:List.of(q.a(),q.b(),q.c(),q.d()))out.append(String.format(Locale.ROOT,"v %.6f %.6f %.6f%n",v.x(),v.y(),v.z()));out.append("f ").append(index).append(' ').append(index+1).append(' ').append(index+2).append(' ').append(index+3).append('\n');index+=4;}
        Files.writeString(Path.of("build/previews/"+name+".obj"),out);
    }
}
