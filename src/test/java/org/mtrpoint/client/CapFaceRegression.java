package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * The end faces of cut and exposed rails.
 *
 * <p>Two levels are sampled. The cap API itself is called for the built-in three-part section, for a
 * native model section, and for a start and an end cut: the quads it emits have to be the real I-beam
 * (solid head, web and foot, the notch beside the web left empty, the full rail height, a material,
 * and the winding facing away from the rail). Then the mesh a frame really draws has to contain such
 * a closed section as well, so a fix on one path cannot hide an open cut on another.
 */
public final class CapFaceRegression {
    private static final List<String> failures=new ArrayList<>();
    private static final PointSettings s=PointSettings.DEFAULT;
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}

    public static void run()throws Exception{
        int sections=0;
        for(var profile:List.of(builtIn(),modelled())){
            String tag=profile.detail()==null?"built-in section":"native model section";
            V3 a=new V3(0,0,0),b=new V3(0,0,.24),direction=b.sub(a),lateral=new V3(1,0,0);
            for(boolean start:new boolean[]{true,false}){
                Mesh mesh=new Mesh();
                V3 centre=start?a:b;
                if(profile.detail()==null)mesh.railCutCap(centre,direction,profile,s,start);
                else mesh.railCap(centre,direction,1,profile,s,"rail",start);
                String where="the "+tag+" end face (start="+start+")";
                check(!mesh.quads.isEmpty(),"no "+where+" was emitted at all");
                if(mesh.quads.isEmpty())continue;
                check(section(mesh.quads,lateral,centre),where+" is not the real I-beam section");
                for(var q:mesh.quads){
                    check(hasMaterial(q),where+" has no material: "+q.surface());
                    V3 n=cross(q.b().sub(q.a()),q.d().sub(q.a()));
                    if(n.dot(n)<1e-18)continue;
                    // The cap at the start of a rail faces backwards, the one at its end forwards: a
                    // reversed winding would render as an invisible back face.
                    check(Math.signum(n.unit().dot(direction.unit()))==(start?-1D:1D),where+" faces the wrong way");
                }
                sections++;
            }
        }

        var tracks=new ArrayList<Track>(Regression.y());
        V3 c=new V3(0,0,-12);
        tracks.add(Regression.line("x1","x1a","x1b",c.add(-30,0,0),c.add(30,0,0)));
        tracks.add(Regression.line("x2","x2a","x2b",c.add(0,0,-30),c.add(0,0,30)));
        var junctions=Detector.find(tracks);
        if(junctions.size()<2)throw new AssertionError("the cap fixture needs a turnout and a crossing");
        for(var profile:List.of(builtIn(),modelled())){
            String tag=profile.detail()==null?"built-in section":"native model section";
            Mesh world=PointRenderer.worldForTest(PointRenderer.viewsForTest(junctions,s,profile));
            var caps=endFaces(world,tracks);
            check(!caps.isEmpty(),"the drawn mesh has no rail end face at all on the "+tag+" ("+world.quads.size()+" quads)");
            int closed=0,open=0;
            for(var group:clusters(caps)){
                if(group.size()<4){open++;continue;}
                if(section(group,lateralOf(group),group.get(0).center()))closed++;else open++;
            }
            check(closed>0,"the drawn mesh on the "+tag+" has no closed rail section ("+open+" partial end faces)");
            System.out.println("     drawn end faces ("+tag+"): "+caps.size()+" quads, "+closed+" closed rail sections, "+open+" partial");
        }
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        System.out.println("PASS: "+sections+" emitted and drawn rail end faces are real I-beam sections"
            +" (head, web and foot solid, notch beside the web empty, full rail height, material present, winding outward)"
            +" for the built-in and the native model section, at a start and at an end cut");
    }

    /** True when these quads close the real rail section: it spans the rail foot to its head, the head,
     *  web and foot are solid and the notch beside the web stays empty. Sampled in the section's own
     *  plane frame, which is what makes the test independent of any part name. */
    static boolean section(List<Mesh.Quad> quads,V3 lateral,V3 origin){
        double minY=9,maxY=-9,minA=9,maxA=-9;
        for(var q:quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d())){
            minY=Math.min(minY,v.y());maxY=Math.max(maxY,v.y());
            double across=v.sub(origin).dot(lateral);minA=Math.min(minA,across);maxA=Math.max(maxA,across);
        }
        double top=Profile.STANDARD.top()+s.verticalOffset(),base=top-Profile.STANDARD.railHeight(),middle=(minA+maxA)/2;
        return Math.abs(minY-base)<.02&&Math.abs(maxY-top)<.02
            &&solid(quads,origin,lateral,middle,maxY-.008)                 // head
            &&solid(quads,origin,lateral,middle,minY+.5*(maxY-minY))       // web
            &&solid(quads,origin,lateral,middle+.05,minY+.012)             // foot, both sides
            &&solid(quads,origin,lateral,middle-.05,minY+.012)
            &&!solid(quads,origin,lateral,middle+.028,minY+.06)            // the notches stay empty
            &&!solid(quads,origin,lateral,middle+.028,minY+.10)
            &&!solid(quads,origin,lateral,middle-.028,minY+.06)
            &&!solid(quads,origin,lateral,middle-.028,minY+.10);
    }
    private static boolean solid(List<Mesh.Quad> quads,V3 origin,V3 lateral,double across,double height){
        V3 flat=origin.add(lateral.mul(across));
        V3 point=new V3(flat.x(),height,flat.z());
        for(var q:quads)if(inside(point,q.a(),q.b(),q.c())||inside(point,q.a(),q.c(),q.d()))return true;
        return false;
    }
    private static boolean inside(V3 p,V3 a,V3 b,V3 c){
        V3 n=cross(b.sub(a),c.sub(a));if(n.dot(n)<1e-18)return false;
        V3 pa=p.sub(a),pb=p.sub(b),pc=p.sub(c);
        double d1=n.dot(cross(pa,pb)),d2=n.dot(cross(pb,pc)),d3=n.dot(cross(pc,pa));
        boolean negative=d1< -1e-12||d2< -1e-12||d3< -1e-12,positive=d1>1e-12||d2>1e-12||d3>1e-12;
        return !(negative&&positive);
    }
    static V3 lateralOf(List<Mesh.Quad> group){
        var shape=group.get(0);
        V3 n=cross(shape.b().sub(shape.a()),shape.d().sub(shape.a())).unit();
        return new V3(n.z(),0,-n.x()).unit();
    }
    /** End faces of the drawn steel, grouped into single sections: a face whose own normal runs along
     *  its rail, split by plane and position so two rails cut at the same station stay apart. */
    static List<List<Mesh.Quad>> clusters(List<Mesh.Quad> caps){
        var byPlane=new LinkedHashMap<String,List<Mesh.Quad>>();
        for(var q:caps){
            V3 n=cross(q.b().sub(q.a()),q.d().sub(q.a())).unit();
            byPlane.computeIfAbsent(round(n)+"@"+round(n.dot(q.center())),k->new ArrayList<>()).add(q);
        }
        var result=new ArrayList<List<Mesh.Quad>>();
        for(var plane:byPlane.values()){
            var remaining=new ArrayList<>(plane);
            while(!remaining.isEmpty()){
                var group=new ArrayList<Mesh.Quad>();group.add(remaining.remove(0));
                for(boolean grown=true;grown;){
                    grown=false;
                    for(int i=remaining.size()-1;i>=0;i--)
                        for(var member:group)if(remaining.get(i).center().distance(member.center())<.06){group.add(remaining.remove(i));grown=true;break;}
                }
                result.add(group);
            }
        }
        return result;
    }
    private static List<Mesh.Quad> endFaces(Mesh mesh,List<Track> tracks){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            if(q.part().equals("sleeper")||q.part().equals("fastener"))continue;
            V3 n=cross(q.b().sub(q.a()),q.d().sub(q.a()));if(n.dot(n)<1e-14)continue;
            n=n.unit();if(Math.abs(n.y())>1e-6)continue;                  // a section face is vertical
            Track road=null;double best=Double.MAX_VALUE;
            for(Track t:tracks){double d=t.nearest(q.center());double away=t.at(d).distance(q.center());if(away<best){best=away;road=t;}}
            if(road==null||best>2)continue;
            double d=road.nearest(q.center());
            if(Math.abs(n.dot(road.tangent(d).unit()))>.9)result.add(q);
        }
        return result;
    }
    private static boolean hasMaterial(Mesh.Quad q){return q.surface()!=null&&q.surface().texture()!=null&&!q.surface().texture().isBlank();}
    private static V3 cross(V3 a,V3 b){return new V3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x());}
    private static double round(double v){return Math.round(v*1e4)/1e4D;}
    private static String round(V3 v){return "("+round(v.x())+","+round(v.y())+","+round(v.z())+")";}

    private static Profile builtIn(){return Profile.STANDARD.tune(s);}
    /** A rail read from a native model: an extruded I-beam in model units, whose zMin section is the
     *  outline the end face has to be built from. */
    private static Profile modelled(){
        double top=Profile.STANDARD.top(),base=top-Profile.STANDARD.railHeight();
        double foot=Profile.STANDARD.footWidth()/2,web=.011,head=Profile.STANDARD.headWidth()/2;
        double footTop=base+.025,headBottom=top-.036,zMin=0,zMax=.24;
        double[][] outline={
            {-foot,base},{foot,base},{foot,footTop},{web,footTop},{web,headBottom},{head,headBottom},
            {head,top},{-head,top},{-head,headBottom},{-web,headBottom},{-web,footTop},{-foot,footTop}
        };
        var rails=new ArrayList<Mesh.Quad>();
        for(int i=0;i<outline.length;i++){
            double[] a=outline[i],b=outline[(i+1)%outline.length];
            V3 a0=new V3(a[0],a[1],zMin),a1=new V3(a[0],a[1],zMax),b0=new V3(b[0],b[1],zMin),b1=new V3(b[0],b[1],zMax);
            rails.add(new Mesh.Quad(a0,a1,b1,b0,Profile.STEEL,"rail",-1));
        }
        var detail=new ModelDetail(rails,List.of(),List.of(),0,top,Profile.STANDARD.headWidth(),zMin,zMax,.12,base,false);
        return new Profile(Profile.STANDARD.gauge(),top,Profile.STANDARD.headWidth(),Profile.STANDARD.footWidth(),
            Profile.STANDARD.railHeight(),Profile.STEEL,Profile.TIMBER,"cap-fixture",false,detail);
    }
}
