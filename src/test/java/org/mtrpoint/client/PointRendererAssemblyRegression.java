package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.*;
import java.util.*;

/**
 * Final-assembly regression for the pooled crossing assembly.
 *
 * <p>PointRenderer.assemble() builds one Mesh for a whole connected component: the crossing's
 * fixed check steel plus every adjoining turnout's guard run, pooled so a guard that meets a wing
 * becomes one rail. This drives assemble() itself through the package-private boundary hook, so it
 * observes the exact quads the frame renderer submits, and it fails whenever the pooled mesh stops
 * reaching the final support mesh.
 *
 * <p>Both fixtures are real junctions. The guard view must be one, because assemble() derives the
 * runs it pools from GuardRails.forJunction(): a hand-made run is never consumed, so it could not
 * prove anything about the pooled assembly.
 */
public final class PointRendererAssemblyRegression {
    private static final double TOP=Profile.STANDARD.top();
    /** Strictly interior (u,v) pairs per baseline top face: no 0, 1 or 0.5 parameter, and neither
     * on a diagonal, so the point is an unambiguous interior sample of the face. Deterministic, so
     * a failure names the same point on every run. */
    private static final double[][] INTERIOR_SAMPLES={{.23,.31},{.61,.37},{.39,.73}};
    /** Half a rail segment away from the first sample of each guard run's head band, so the
     * longitudinal sweep never starts on the seam between two of the reference run's own faces. */
    private static final double GUARD_SWEEP_OFFSET=.11;

    public static void run()throws Exception{
        Profile p=Profile.STANDARD.tune(PointSettings.DEFAULT);
        Track h=new Track("final-h","h0","h1",steps(new V3(-20,0,0),new V3(20,0,0),.25));
        Track v=new Track("final-v","v0","v1",steps(new V3(0,0,-20),new V3(0,0,20),.25));
        Junction diamond=Detector.find(List.of(h,v)).stream().filter(j->j.kind()==Junction.Kind.DIAMOND).findFirst().orElseThrow();

        // The adjoining turnout stands clear of the crossing, so its guard runs join the crossing's
        // check pool by style, not by position, and the pooled steel is what has to reach the final
        // support mesh exactly once. Elevating or overlapping it would let the bake cut one span
        // with the other's planes and hide real losses behind fixture damage.
        Track guardMain=Regression.line("final-guard-main","g0","g1",new V3(60,0,0),new V3(60,0,30));
        var guardBranch=new ArrayList<V3>();
        for(int i=0;i<=120;i++){double z=i*.25,x=.0105*z*z;guardBranch.add(new V3(60+x,0,z));}
        Junction guardJunction=new Junction("final-guard",Junction.Kind.Y,guardMain,new Track("final-guard-branch","g0","g2",guardBranch),new V3(60,0,0),0,0,30);

        // assemble() pools exactly the runs GuardRails.forJunction() reports for the view, so the
        // mesh of those runs is the reference the pooled check steel must match.
        var guardRuns=GuardRails.forJunction(guardJunction,PointSettings.DEFAULT,p);
        require(!guardRuns.isEmpty(),"Guard fixture produced no check runs");
        Mesh guardAlone=DiamondGeometry.guards(guardRuns);
        var guardTops=tops(guardAlone,"guard");
        require(!guardTops.isEmpty(),"Guard fixture produced no check steel");

        // The assembly used before pooling existed. It holds exactly the crossing steel the pooled
        // assembly must also carry, so every top face it owns is a required coverage sample.
        Mesh baseline=DiamondGeometry.combine(List.of(new DiamondGeometry.Request(diamond,PointSettings.DEFAULT,p,PointMesh.extent(diamond,PointSettings.DEFAULT))));
        var baselineTops=tops(baseline);
        require(baselineTops.stream().anyMatch(q->q.part().equals("frog"))&&baselineTops.stream().anyMatch(q->q.part().equals("wing")),"Crossing fixture has no frog or wing steel to lose");

        Mesh assembled=PointRenderer.assembleForTest(List.of(
            PointRenderer.view(diamond,PointSettings.DEFAULT,p,PointMesh.build(diamond,PointSettings.DEFAULT,p,0),null),
            PointRenderer.view(guardJunction,PointSettings.DEFAULT,p,PointMesh.build(guardJunction,PointSettings.DEFAULT,p,0),null)));

        var assembledTops=tops(assembled);
        require(!assembledTops.isEmpty(),"Final assembly carries no steel at all");

        // A quad vertex is not a usable sample: two valid tessellations of the same surface meet a
        // different number of faces at a shared vertex, so a face count taken there follows the
        // tessellation and not the steel. Every sample is instead a generic interior point of one
        // baseline top face, away from the corners, the mid-edges and both diagonals, where the
        // count is simply the number of faces covering that area of rail.
        int crossingSamples=0;var frog=new boolean[1];var guard=new boolean[1];
        for(var face:baselineTops)for(double[] uv:INTERIOR_SAMPLES){
            V3 point=bilinear(face,uv[0],uv[1]);
            int want=coverage(baselineTops,point);
            if(want<1)continue;
            int got=coverage(assembledTops,point);
            require(got>=1,"Final assembly dropped crossing steel at "+point+" inside a "+face.part()+" face: "+got+" faces, expected "+want);
            require(got<=want,"Final assembly duplicated the pooled crossing mesh at "+point+": "+got+" faces, expected "+want);
            crossingSamples++;
            labelled(assembledTops,point,"frog",frog);
            // The pool turns the crossing's check-side wings into one shared guard rail, so the
            // surviving check steel is tagged guard rather than wing in the pooled assembly.
            labelled(assembledTops,point,"guard",guard);
        }
        require(crossingSamples>200,"Crossing fixture too thin to prove anything: "+crossingSamples);
        require(frog[0]&&guard[0],"Pooled assembly carries no frog or guard steel");

        // Degenerate faces are excluded from tops(), so the guard band is proven separately: each
        // run's own centre line plus the whole head width, swept at a fine longitudinal and lateral
        // step. The sweep is offset off the exact head edge and off the sample spacing that the
        // rail's own segment boundaries are built from: a point landing exactly on a seam or on a
        // head edge is covered by a different number of faces in each valid tessellation of the
        // same steel, so only interior points of the band give a face count that means anything.
        var assembledGuard=tops(assembled,"guard");
        int guardSamples=0;
        for(var run:guardRuns){
            for(double d=run.start()+GUARD_SWEEP_OFFSET;d<run.end()-.6;d+=.02)for(int step=0;step<=32;step++){
                double lateral=-.024+step*.0015;
                V3 point=run.center(d).add(run.road().tangent(d).lateral().mul(lateral));
                int want=coverage(guardTops,point);
                if(want<1)continue;
                int got=coverage(assembledGuard,point);
                require(got>=1,"Final assembly dropped pooled guard steel at "+point);
                require(got<=want,"Final assembly duplicated the pooled guard mesh at "+point+": "+got+" faces, expected "+want);
                guardSamples++;
            }
        }
        require(guardSamples>500,"Guard fixture too thin to prove anything: "+guardSamples);
        System.out.println("PASS: pooled final assembly keeps "+crossingSamples+" crossing and "+guardSamples+" guard samples with no duplicate check steel");
    }

    /** Does the assembled mesh own a face of this part under the query point? */
    private static void labelled(List<Mesh.Quad> faces,V3 point,String part,boolean[] found){
        if(found[0])return;
        for(var quad:faces)if(quad.part().equals(part)&&coverage(List.of(quad),point)>0){found[0]=true;return;}
    }

    private static List<V3> vertices(Mesh.Quad q){return List.of(q.a(),q.b(),q.c(),q.d());}
    /** Bilinear point of a quad at parameters (u,v). Neither parameter is 0, 1 or 0.5, so the
     * point stays off the corners and the mid-edges, and u+v=1 and u=v, which are the two
     * diagonals, are avoided as well. */
    private static V3 bilinear(Mesh.Quad q,double u,double v){
        return q.a().mul((1-u)*(1-v)).add(q.b().mul(u*(1-v))).add(q.c().mul(u*v)).add(q.d().mul((1-u)*v));
    }
    /** Flat, finite upper faces at the working rail top; used as the coverage mask. */
    private static List<Mesh.Quad> tops(Mesh mesh,String... parts){
        var result=new ArrayList<Mesh.Quad>();
        var wanted=Set.of(parts);
        for(var quad:mesh.quads){
            if(!wanted.isEmpty()&&!wanted.contains(quad.part()))continue;
            boolean flat=true;
            for(V3 vertex:vertices(quad))if(Math.abs(vertex.y()-TOP)>1e-8||!Double.isFinite(vertex.x()+vertex.y()+vertex.z())){flat=false;break;}
            if(flat)result.add(quad);
        }
        return result;
    }
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    /** Local head-footprint oracle: the org.mtrpoint one is package-private to its own tree. */
    private static int coverage(List<Mesh.Quad> faces,V3 point){return coverage(faces,point,1e-9);}
    private static int coverage(List<Mesh.Quad> faces,V3 point,double tolerance){int count=0;for(var q:faces)if(triangle(point,q.a(),q.b(),q.c(),tolerance)||triangle(point,q.a(),q.c(),q.d(),tolerance))count++;return count;}
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){return triangle(p,a,b,c,1e-9);}
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c,double tolerance){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-tolerance&&v>=-tolerance&&u+v<=1+tolerance;
    }
    private static List<V3> steps(V3 a,V3 b,double step){
        var points=new ArrayList<V3>();int count=(int)Math.ceil(a.distance(b)/step);
        for(int i=0;i<=count;i++)points.add(a.lerp(b,i/(double)count));
        return points;
    }
}
