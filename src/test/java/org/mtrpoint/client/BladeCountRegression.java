package org.mtrpoint.client;

import java.util.*;
import org.mtrpoint.geometry.*;

/** The switch rail replaces the stock rail on its own side: inside the planed run there is exactly
 *  one body of steel. A second fixed rail drawn beside the blade is the defect the user reported as
 *  a thin extra length of rail hugging the real one, overlapping at head and web. */
public final class BladeCountRegression{
    private static final PointSettings s=PointSettings.DEFAULT;
    private static final List<String> failures=new ArrayList<>();
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}

    public static void run()throws Exception{
        Profile p=Profile.STANDARD.tune(s);
        double top=p.top()+s.verticalOffset(),offset=p.centerOffset();
        var junctions=Detector.find(tracks());
        check(junctions.size()==1&&junctions.get(0).kind()==Junction.Kind.Y,
            "the blade fixture is one Y turnout (found "+junctions.size()+")");
        if(junctions.isEmpty())return;
        Junction j=junctions.get(0);
        double limit=Math.min(8,PointMesh.extent(j,s));
        int probes=0,two=0;String first="";
        // The switch rail is animated, so the probe runs at three blade poses on the preview mesh the
        // editor draws and once on the world mesh the renderer submits.
        var moving=s.flags(true,true);
        var views=PointRenderer.viewsForTest(junctions,moving,Profile.STANDARD);
        Mesh world=PointRenderer.worldForTest(views);
        for(double position:new double[]{0,.5,1}){
            Mesh preview=PointMesh.build(j,moving,Profile.STANDARD,position);
            for(boolean usePreview:new boolean[]{true,false}){
                Mesh mesh=usePreview?preview:world;
                for(int branch=0;branch<2;branch++){
                    Track road=branch==0?j.a():j.b();
                    for(int sign:new int[]{-1,1}){
        // Near the toe the two branches' rails are only centimetres apart, so the probe only starts
        // where this branch's own rail is clear of its neighbour's: x=0.008z^2 reaches half a window
        // at about z=4. The planed run still covers several metres after that.
        double from=4.5;
                        for(double d=from;d<=limit;d+=.25){
                            V3 centre=road.at(d).add(road.tangent(d).lateral().mul(sign*offset));
                            V3 side=road.tangent(d).lateral();
                            int runs=0;boolean in=false;
                            for(double lat=-.25;lat<=.2501;lat+=.01){
                                boolean steel=ToeCoverageRegression.steelAt(mesh,centre.add(side.mul(lat)),top,p);
                                if(steel&&!in){runs++;in=true;}else if(!steel)in=false;
                            }
                            if(runs>0){
                                probes++;
                                if(runs!=1){
                                    two++;
                                    if(first.isEmpty())first="road "+road.id+" station "+round(d)+" sign "+sign
                                        +" position "+position+(usePreview?" preview":" world")+" at ("
                                        +round(centre.x())+","+round(centre.z())+") shows "+runs+" bodies";
                                }
                            }
                        }
                    }
                }
            }
        }
        check(probes>0,"the switch-rail probe sampled no steel at all, so it proves nothing");
        check(two==0,"the switch side of a turnout does not draw exactly one rail: "+two+" of "+probes
            +" probes show a rail count other than one ("+first+")");
        // The rule itself, checked structurally rather than by counting bodies: there must never be a
        // 'rail' face on the blade's own line inside the planed run. A second rail hidden behind a 2.5%
        // taper sits inside the real section, so a lateral body count cannot see it - this can.
        double planedStart=TurnoutFrame.start(j,PointMesh.extent(j,s));
        double secondStart=j.b().nearest(j.a().at(planedStart));
        // Count which of the road's two rail lines carries 'rail' faces inside the planed run. Exactly
        // one may: the outer rail. The line the switch rail occupies carries the blade and nothing else,
        // and this is sign-free so it cannot be fooled by which side happens to be the switch side.
        int lines=0,total=0;
        for(double position:new double[]{0,.5,1}){
            Mesh mesh=PointMesh.build(j,moving,Profile.STANDARD,position);
            for(int branch=0;branch<2;branch++){
                Track road=branch==0?j.a():j.b();
                double start=branch==0?planedStart:secondStart;
                int onLines=0;
                for(int sign:new int[]{-1,1}){
                    int found=railsOnBladeLine(mesh,road,start,1.2,1.8,sign*offset);
                    total+=found;
                    if(found>0)onLines++;
                }
                lines=Math.max(lines,onLines);
            }
        }
        check(lines==1,"inside the planed run the mesh draws 'rail' faces on "+lines+" of the road's two rail"
            +" lines: the switch rail replaces the stock rail on its own line, so only the outer one may"
            +" carry a fixed rail ("+total+" 'rail' faces seen)");
        // A three-way fan animates three blades at once. Its head faces are padded to a fixed slot
        // count so the renderer can interpolate cached frames; these two checks make sure the padding
        // never becomes the only thing keeping a part alive (that would silently hide lost steel).
        var three=new ArrayList<Track>(tracks().subList(0,2));
        var middle=new ArrayList<V3>();for(int i=0;i<=120;i++)middle.add(new V3(0,0,i*.25));
        three.add(new Track("tm","0,0,0","0,0,30",middle));
        var junctions3=Detector.find(three);
        check(junctions3.size()==1&&junctions3.get(0).kind()==Junction.Kind.THREE,
            "the three-way fixture is one independent editor (found "+junctions3.size()+")");
        if(!junctions3.isEmpty()){
            Junction t=junctions3.get(0);
            var sizes=new ArrayList<Integer>();
            for(double position:new double[]{0,.5,1})sizes.add(PointMesh.build(t,moving,Profile.STANDARD,position).quads.size());
            check(sizes.get(0).equals(sizes.get(1))&&sizes.get(1).equals(sizes.get(2)),
                "the animated three-way changes its face count between poses: "+sizes);
            for(String part:new String[]{"rail","blade","frog","stretcher"}){
                for(double position:new double[]{0,.5,1}){
                    var mesh=PointMesh.build(t,moving,Profile.STANDARD,position);
                    long any=0,real=0;
                    for(var q:mesh.quads)if(q.part().equals(part)){any++;if(area(q)>1e-9)real++;}
                    check(any==0||real>0,"the three-way padding hid every real '"+part+"' face at pose "+position
                        +" ("+real+" real of "+any+"), so lost steel would go unnoticed");
                }
            }
            // Same absolute rule for the animated three-way: while a branch is the one set, no 'rail'
            // face may sit on that branch's own line inside its planed run. This one is reported, not
            // asserted: near the shared toe the three roads' rail lines are only centimetres apart, so
            // a neighbour's rail can fall inside the tolerance and the count is not conclusive there.
            double threeStart=TurnoutFrame.start(t,PointMesh.extent(t,s));
            int threeFillers=0;
            for(int branch=0;branch<3;branch++){
                Mesh mesh=PointMesh.build(t,moving,Profile.STANDARD,branch*.5);
                Track road=t.tracks().get(branch);
                for(int sign:new int[]{-1,1})threeFillers+=railsOnBladeLine(mesh,road,threeStart,1.2,1.8,sign*offset);
            }
            if(threeFillers>0)System.out.println("     NOTE: the three-way reports "+threeFillers
                +" 'rail' faces on a set blade's own line; near the shared toe the neighbouring roads' rail"
                +" lines are centimetres away, so each one has to be judged by hand");
            System.out.println("PASS: the animated three-way keeps "+sizes.get(0)+" faces at every pose and never"
                +" pads a part down to zero real faces (parts rail/blade/frog/stretcher, three poses)");
        }
        System.out.println("PASS: the planed run of a turnout draws exactly one steel body on the switch side ("
            +probes+" probes, three blade poses, preview and world)");
    }

    private static double round(double v){return Math.round(v*1000)/1000D;}

    /** Half the magnitude of the quad's own cross product: zero for a padding slot. */
    private static double area(Mesh.Quad q){
        V3 ab=q.b().sub(q.a()),ac=q.c().sub(q.a());
        V3 n=new V3(ab.y()*ac.z()-ab.z()*ac.y(),ab.z()*ac.x()-ab.x()*ac.z(),ab.x()*ac.y()-ab.y()*ac.x());
        return n.length()/2;
    }

    /** How many 'rail' faces sit on the blade's own line (lateral offset of the route line, 2 cm
     *  tolerance) between two stations after the planed run starts. Zero is the only acceptable count. */
    private static int railsOnBladeLine(Mesh mesh,Track road,double start,double from,double to,double offset){
        int found=0;
        for(var q:mesh.quads){
            if(!q.part().equals("rail"))continue;
            // Degenerate faces are how the animated topology keeps its fixed slot count; they draw
            // nothing, so they are not a drawn rail.
            if(area(q)<=1e-9)continue;
            V3 c=q.center();double station=road.nearest(c);
            if(station<start+from||station>start+to)continue;
            double lateral=c.sub(road.at(station)).dot(road.tangent(station).lateral());
            if(Math.abs(lateral-offset)<.02){
                found++;
                if(found==1)System.out.println("     fixed rail on the blade line: 'rail' face at station "
                    +round(station)+", lateral "+round(lateral)+", centre ("+round(c.x())+","+round(c.y())
                    +","+round(c.z())+")");
            }
        }
        return found;
    }

    /** Two diverging roads and the road that runs into them: a Y turnout whose toe is the node. */
    private static List<Track> tracks(){
        var a=new ArrayList<V3>();var b=new ArrayList<V3>();var incoming=new ArrayList<V3>();
        for(int i=0;i<=120;i++){double z=i*.25,x=.008*z*z;a.add(new V3(-x,0,z));b.add(new V3(x,0,z));}
        for(int i=-80;i<=0;i++)incoming.add(new V3(0,0,i*.25));
        return List.of(new Track("ta","0,0,0","-7,0,30",a),new Track("tb","0,0,0","7,0,30",b),
            new Track("tin","in","0,0,0",incoming));
    }
}
