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
        for(double position:new double[]{0,.25,.5,.75,1})require(PointMesh.build(y,moving,Profile.STANDARD,position).quads.size()==PointMesh.build(y,moving,Profile.STANDARD,0).quads.size(),"Stable movable frog topology");
        for(double position:new double[]{0,1}){
            Mesh heart=PointMesh.build(y,moving,Profile.STANDARD,position);
            var frogFaces=heart.quads.stream().filter(q->q.part().equals("frog")).toList();var wingFaces=heart.quads.stream().filter(q->q.part().equals("wing")).toList();
            V3 tip=frogFaces.get(12).a().lerp(frogFaces.get(12).d(),.5);double contact=Double.MAX_VALUE;
            for(int k=12;k<wingFaces.size();k+=18){var q=wingFaces.get(k);contact=Math.min(contact,Math.min(segmentDistance(tip,q.a(),q.b()),segmentDistance(tip,q.d(),q.c())));}
            require(contact<.003,"Movable heart point touches selected wing edge: "+contact);
        }
        require(left.quads.stream().filter(q->q.part().equals("wing")).count()>20,"Continuous wing rail assembly exists");
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
            Mesh a=group.clip(PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,0,group.boundary(y,PointSettings.DEFAULT)),y),b=group.clip(PointMesh.build(y,PointSettings.DEFAULT,Profile.STANDARD,1,group.boundary(y,PointSettings.DEFAULT)),y);
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
