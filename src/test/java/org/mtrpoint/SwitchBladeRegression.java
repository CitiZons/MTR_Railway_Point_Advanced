package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * The running surface the player sees: continuous through the crossing and planed only where the
 * switch rail really touches the stock rail.
 *
 * <p>Both cases are measured on the mesh the client renders, at the running-top height, so a face
 * that merely exists in some intermediate list cannot satisfy them.
 */
public final class SwitchBladeRegression {
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    private static String fmt(V3 v){return "("+Math.round(v.x()*1000)/1000D+","+Math.round(v.y()*1000)/1000D+","+Math.round(v.z()*1000)/1000D+")";}

    public static void run(){
        Profile raw=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        for(double curve:new double[]{.0185,-.0185}){
            String label=curve>0?"diverging left":"diverging right";
            Junction j=turnout(curve);
            for(double position:new double[]{0,1})runningSurface(label,j,s,raw,position);
            planing(label,j,s,raw);
        }
        PointSettings custom=s.with(10,.076);
        planing("custom head width",turnout(.0185),custom,raw);
        double blade=9,contact=TurnoutFrame.contact(blade,raw.tune(s),s);
        require(contact>2.1&&contact<2.35,"Contact length of a 9 m switch rail must stay the short tip region, got "+contact);
        require(contact<blade*.3,"Contact length must be a fraction of the switch rail, got "+contact+" of "+blade);
        require(Math.abs(TurnoutFrame.contact(blade,raw.tune(s),s)-TurnoutFrame.contact(blade,raw.tune(s.with(10,.076)),s))>.2,"A wider head must move the contact length");
        System.out.println("PASS: switch-rail contact region is the planed tip only, for the default and a custom section");
    }

    /** Every station of the route in service must carry running-surface steel: the old geometry left
     *  a hollow between the wing knee and the crossing nose, on both inner rails. */
    private static void runningSurface(String label,Junction j,PointSettings s,Profile raw,double position){
        Profile p=raw.tune(s);Mesh mesh=PointMesh.build(j,s,raw,position);
        double extent=PointMesh.extent(j,s),offset=p.centerOffset(),top=p.top()+s.verticalOffset();
        var heads=surfaceTops(mesh,top);
        int branch=position<.5?0:1;Track road=branch==0?j.a():j.b();
        require(extent>20,"Fixture lost its length: "+extent);
        for(int sign:new int[]{-1,1}){
            double worst=0,worstAt=0,run=0,slipped=0,slippedAt=0;
            for(double d=.05;d<extent-.05;d+=.02){
                V3 q=road.at(d).add(road.tangent(d).lateral().mul(sign*offset));
                boolean on=cover(heads,q)>0;
                if(!on){
                    // A rail may sit a little off the nominal running line, but it may never be absent:
                    // search a narrow band before calling it a hole.
                    V3 base=road.at(d),side=road.tangent(d).lateral();
                    for(double lat=-.06;lat<=.0601&&!on;lat+=.02)if(cover(heads,base.add(side.mul(sign*offset+lat)))>0)on=true;
                    if(on){slipped=Math.max(slipped,.06);slippedAt=d;}
                }
                if(!on){run+=.02;if(run>worst){worst=run;worstAt=d;}}else run=0;
            }
            if(worst>=.03){
                // Where is the steel that should be here? Report the lateral offsets that still carry
                // running surface at the worst station, so a displaced rail can be told from a hole.
                V3 base=road.at(worstAt),side=road.tangent(worstAt).lateral();
                StringBuilder found=new StringBuilder();
                for(double lat=-.30;lat<=.3001;lat+=.05){
                    if(cover(heads,base.add(side.mul(sign*offset+lat)))>0)found.append(found.length()==0?"":",").append(Math.round(lat*100)/100D);
                }
                String part="?";Mesh.Quad hit=null;
                for(var h:heads){
                    if(cover(List.of(h),base.add(side.mul(sign*offset-.05)))>0){part=h.part();hit=h;break;}
                }
                String corners=hit==null?"none":("["+fmt(hit.a())+" "+fmt(hit.b())+" "+fmt(hit.c())+" "+fmt(hit.d())+"]");
                require(false,label+" at position "+position+": the route in service has a "+worst
                    +" m hollow on branch "+branch+" side "+sign+" ending at station "+worstAt
                    +"; steel at "+worstAt+" sits at lateral offsets ["+(found.length()==0?"none":found.toString())
                    +"] and is part '"+part+"' corners "+corners+" (rail id "+road.id+")");
            }
        }
        System.out.println("PASS: "+label+" position "+position+": branch "+branch+" running surface continuous over "+extent+" m on both rails");
    }

    /** The switch rail is planed at the tip only: full section, one owner face and a continuous
     *  width profile past the contact region, identical at every animation frame. */
    private static void planing(String label,Junction j,PointSettings s,Profile raw){
        Profile p=raw.tune(s);double extent=PointMesh.extent(j,s),top=p.top()+s.verticalOffset();
        double side=TurnoutFrame.side(j,extent);Track road=j.a();
        var profiles=new ArrayList<List<double[]>>();
        for(double position:new double[]{0,.5,1}){
            Mesh mesh=PointMesh.build(j,s,raw,position);
            var faces=new ArrayList<double[]>();
            for(var q:mesh.quads){
                if(!q.part().equals("blade")||!flat(q,top))continue;
                double center=road.nearest(q.center());
                double lateral=q.center().sub(road.at(center)).dot(road.tangent(center).lateral())*side;
                if(lateral<.4)continue;
                faces.add(new double[]{road.nearest(q.a()),road.nearest(q.b()),q.a().distance(q.d()),q.b().distance(q.c())});
            }
            require(faces.size()>2,label+": switch rail has no head faces at position "+position);
            faces.sort(Comparator.comparingDouble(f->f[0]));
            profiles.add(faces);
        }
        double headWidth=p.headWidth(),tipWidth=headWidth*TurnoutFrame.TIP_TAPER;
        for(List<double[]> faces:profiles){
            double bladeStart=faces.get(0)[0];
            require(Math.abs(faces.get(0)[2]-tipWidth)<1e-9,"The switch rail tip must be planed to "+tipWidth+", measured "+faces.get(0)[2]);
            require(faces.get(0)[2]<headWidth*.1,"A switch rail tip must not keep its full section");
            double firstFull=Double.NaN,bladeEnd=bladeStart;
            for(int i=0;i<faces.size();i++){
                double w0=faces.get(i)[2],w1=faces.get(i)[3];
                require(w0>tipWidth-1e-9&&w0<=headWidth+1e-9&&w1>tipWidth-1e-9&&w1<=headWidth+1e-9,"Switch rail width out of range: "+w0+","+w1);
                require(w1>=w0-1e-12,"Switch rail narrows again past its tip: "+w0+" -> "+w1);
                if(i>0){require(Math.abs(faces.get(i-1)[3]-w0)<1e-9,"Switch rail width jumps between segments: "+faces.get(i-1)[3]+" vs "+w0);
                    require(faces.get(i)[0]>=faces.get(i-1)[0]-1e-9,"Switch rail stations out of order");}
                if(Double.isNaN(firstFull)&&Math.abs(w0-headWidth)<1e-9)firstFull=faces.get(i)[0];
                bladeEnd=Math.max(bladeEnd,faces.get(i)[1]);
            }
            require(!Double.isNaN(firstFull),"The switch rail never reaches its full section");
            require(Math.abs(faces.get(faces.size()-1)[3]-headWidth)<1e-9,"The switch rail must end at its full section");
            double blade=bladeEnd-bladeStart,planed=firstFull-bladeStart,contact=TurnoutFrame.contact(blade,p,s);
            require(blade>4,"Fixture switch rail too short: "+blade);
            require(planed<=contact+.45,"Full section must start at the end of the contact region ("+contact+"), measured "+planed);
            require(planed>=contact-.2,"The contact region must be planed for its whole length ("+contact+"), measured "+planed);
            require(planed<=blade*.35,"The whole switch rail is tapered again: planed "+planed+" of a "+blade+" m rail");
        }
        for(int i=1;i<profiles.size();i++)for(int k=0;k<profiles.get(i).size();k++){
            double[] a=profiles.get(0).get(k),b=profiles.get(i).get(k);
            require(Math.abs(a[0]-b[0])<5e-3&&Math.abs(a[1]-b[1])<5e-3,"Switch-rail stations move with the animation frame: "+Arrays.toString(a)+" vs "+Arrays.toString(b));
            for(int c=2;c<4;c++)require(Math.abs(a[c]-b[c])<1e-9,"Switch-rail section profile changes with the animation frame: "+Arrays.toString(a)+" vs "+Arrays.toString(b));
        }
        System.out.println("PASS: "+label+": switch rail planed over "+trim(profiles.get(0).get(0)[0])+"+"+"[tip], full section beyond the contact region, stable at every frame");
    }
    private static String trim(double value){return String.format("%.3f",value);}
    private static boolean flat(Mesh.Quad q,double height){
        for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))if(Math.abs(v.y()-height)>1e-8)return false;
        return true;
    }
    private static List<Mesh.Quad> surfaceTops(Mesh mesh,double height){
        var result=new ArrayList<Mesh.Quad>();
        for(var q:mesh.quads){
            if(q.part().equals("sleeper")||q.part().equals("fastener")||q.part().equals("stretcher"))continue;
            if(flat(q,height))result.add(q);
        }
        return result;
    }
    private static int cover(List<Mesh.Quad> faces,V3 point){
        int count=0;for(var q:faces)if(triangle(point,q.a(),q.b(),q.c())||triangle(point,q.a(),q.c(),q.d()))count++;
        return count;
    }
    private static boolean triangle(V3 p,V3 a,V3 b,V3 c){
        double area=V3.crossXZ(b.sub(a),c.sub(a));if(Math.abs(area)<1e-12)return false;
        double u=V3.crossXZ(p.sub(a),c.sub(a))/area,v=V3.crossXZ(b.sub(a),p.sub(a))/area;
        return u>=-1e-9&&v>=-1e-9&&u+v<=1+1e-9;
    }
    /** A turnout with a common approach, so the seam near the switch blade is exercised as well. */
    private static Junction turnout(double curve){
        Track a=Regression.line("sw-a","n0","a1",new V3(0,0,-6),new V3(0,0,32));
        var points=new ArrayList<V3>();
        for(int i=0;i<=152;i++){double z=-6+38*i/152.0,dz=Math.max(0,z-14);points.add(new V3(curve*dz*dz,0,z));}
        Track b=new Track("sw-b","n0","b1",points);
        var junctions=Detector.find(List.of(a,b));
        require(junctions.size()==1&&junctions.get(0).kind()==Junction.Kind.Y,"Switch fixture is not a single turnout: "+junctions.size());
        return junctions.get(0);
    }
}
