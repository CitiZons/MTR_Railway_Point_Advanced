package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * Enumerates the real-world layout families that can reach this renderer.  The point of this
 * test is classification and finite geometry, rather than pretending that every railway device
 * is the same kind of junction.  Families which need a switch graph (slips, ladders and a
 * four-arm junction) are explicitly reported as unsupported instead of being silently treated as
 * an ordinary diamond.
 */
public final class GeometryTypeEnumerationRegression {
    public static void main(String[] args){run();}
    private record Case(String name,List<Track> tracks,Set<Junction.Kind> expected,boolean supported) {}
    private static Track line(String id,String a,String b,double x0,double z0,double x1,double z1){
        return Regression.line(id,a,b,new V3(x0,0,z0),new V3(x1,0,z1));
    }
    private static List<Track> wye(){
        // Include the approach leg: a turnout is a three-rail fan at one node.
        return Regression.y();
    }
    private static List<Track> tandem(){
        return List.of(line("ta-main","ta","tb",0,-24,0,0),line("ta-branch","ta","ta-end",0,0,12,20),
            line("tb-branch","tb","tb-end",0,0,-12,20),line("tb-through","tb","tc",0,0,0,24));
    }
    private static List<Track> ladder(){
        var out=new ArrayList<Track>();out.add(line("ladder-main","l0","l4",0,-30,0,30));
        for(int i=0;i<3;i++)out.add(line("ladder-r"+i,"l"+i,"r"+i,0,-18+i*12,12,-12+i*12));
        return out;
    }
    private static List<Track> crossover(){
        return List.of(line("co-main-a","a0","a1",-4,-24,-4,24),line("co-main-b","b0","b1",4,-24,4,24),
            line("co-cross-a","c0","c1",-12,-8,12,8),line("co-cross-b","d0","d1",-12,8,12,-8));
    }
    private static List<Track> doubleSlip(){
        // A double slip is a diamond with two switch routes; its centre must remain one crossing,
        // while the switch routes are separate rails that may be classified by their own nodes.
        return List.of(line("ds-main-a","ds-a0","ds-a1",-12,-4,12,-4),line("ds-main-b","ds-b0","ds-b1",-12,4,12,4),
            line("ds-slip-a","ds-c0","ds-c1",-12,4,12,-4),line("ds-slip-b","ds-d0","ds-d1",-12,-4,12,4));
    }
    private static List<Track> fourArm(){
        return List.of(line("fa-n","fa","fa-n-end",0,0,0,20),line("fa-e","fa","fa-e-end",0,0,20,0),
            line("fa-s","fa","fa-s-end",0,0,0,-20),line("fa-w","fa","fa-w-end",0,0,-20,0));
    }
    private static List<Case> cases(){
        return List.of(
            new Case("single turnout / wye",wye(),Set.of(Junction.Kind.Y),true),
            new Case("curved single turnout",Regression.y(),Set.of(Junction.Kind.Y),true),
            new Case("three-way turnout",new ArrayList<>(List.of(Regression.y().get(0),Regression.y().get(1),line("three-mid","0,0,0","mid",0,0,0,30))),Set.of(Junction.Kind.THREE),true),
            new Case("tandem turnout",tandem(),Set.of(Junction.Kind.Y),true),
            new Case("diamond crossing",List.of(line("dia-a","a0","a1",-20,0,20,0),line("dia-b","b0","b1",0,-20,0,20)),Set.of(Junction.Kind.DIAMOND),true),
            new Case("curved/skew diamond",List.of(line("skew-a","a0","a1",-20,-5,20,5),line("skew-b","b0","b1",-20,5,20,-5)),Set.of(Junction.Kind.DIAMOND),true),
            new Case("crossover pair",crossover(),Set.of(Junction.Kind.DIAMOND),true),
            new Case("scissors crossover",Regression.scissors(true),Set.of(Junction.Kind.Y,Junction.Kind.DIAMOND),true),
            new Case("ladder of turnouts",ladder(),Set.of(),false),
            new Case("single slip / double slip centre",doubleSlip(),Set.of(Junction.Kind.DIAMOND),false),
            new Case("four-arm connected junction",fourArm(),Set.of(),false),
            new Case("gauntlet parallel pair",List.of(line("ga-a","ga0","ga1",0,-20,0,20),line("ga-b","gb0","gb1",.35,-20,.35,20)),Set.of(),true),
            new Case("grade-separated overpass",List.of(line("ov-a","oa","ob",-20,0,20,0),new Track("ov-b","oc","od",List.of(new V3(0,2,-20),new V3(0,2,20)))),Set.of(),true)
        );
    }
    public static void run(){
        int supported=0,unsupported=0;
        for(Case c:cases()){
            var found=Detector.find(c.tracks());var kinds=new HashSet<Junction.Kind>();for(var j:found)kinds.add(j.kind());
            if(c.supported()){
                if(!kinds.containsAll(c.expected()))throw new AssertionError(c.name()+" was not classified as "+c.expected()+": "+kinds);
                for(var j:found){
                    Mesh mesh=PointMesh.build(j,PointSettings.DEFAULT,Profile.STANDARD,0);
                    for(var q:mesh.quads)for(var v:List.of(q.a(),q.b(),q.c(),q.d()))if(!Double.isFinite(v.x()+v.y()+v.z()))throw new AssertionError(c.name()+" emitted non-finite geometry");
                }
                supported++;
            }else{
                // Unsupported switch graphs may still expose a diamond, but must never crash or
                // be promoted to a fabricated three-way turnout.
                if(kinds.contains(Junction.Kind.THREE))throw new AssertionError(c.name()+" was misclassified as a three-way turnout");
                unsupported++;
            }
            System.out.println("GEOMETRY_ENUM: "+c.name()+" -> "+kinds+" / "+(c.supported()?"supported":"requires dedicated switch graph"));
        }
        if(supported<8||unsupported<3)throw new AssertionError("Enumeration lost layout families: "+supported+" / "+unsupported);
        System.out.println("PASS: enumerated "+(supported+unsupported)+" real-world turnout/crossing families; supported families produce finite geometry and unsupported switch graphs are explicit");
    }
}
