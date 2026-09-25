package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;
import java.util.*;

/**
 * The cutter itself: a crossing road at the same running height has to remove the flange way from the
 * running steel it crosses, and only that - the steel outside stays continuous, and no shaving is
 * left inside the gap. Both the running-rail path ({@code cutChannels}, the call the view mesh uses)
 * and the check-steel path ({@code cutSteel}, the call the shared guard pool uses) are sampled, on
 * the built-in section and on a native model section.
 */
public final class ChannelCutRegression {
    private static final List<String> failures=new ArrayList<>();
    private static final PointSettings s=PointSettings.DEFAULT;
    private static void check(boolean pass,String why){if(!pass){failures.add(why);System.out.println("FAIL: "+why);}}

    public static void run()throws Exception{
        for(var profile:List.of(Profile.STANDARD.tune(s),modelled())){
            String tag=profile.detail()==null?"built-in section":"native model section";
            double top=profile.top()+s.verticalOffset(),offset=profile.centerOffset(),gap=Math.max(.02,s.flangeway()+s.wingGapDelta());
            // The flange way of a road sits beside its own rail: centreOffset - head/2 - gap/2 off the
            // road centre, one gap wide. That is the strip the cutter has to open and nothing else.
            double centre=offset-profile.headWidth()/2-gap/2;
            // Road A runs along z with its two running rails; road B crosses it along x at the same
            // height. B's flange ways therefore cut A's rail where the two really meet.
            Track b=Regression.line("b","-8,0,0","8,0,0",new V3(-8,0,0),new V3(8,0,0));
            double inside=0,outside=0,samples=0,missing=0;
            var blocked=new ArrayList<String>();
            for(int sign:new int[]{-1,1}){
                Mesh rail=new Mesh();rail.rail(new V3(sign*offset,0,-2),new V3(sign*offset,0,2),1,1,profile,s,"rail");
                Mesh cut=DiamondGeometry.cutChannels(rail,List.of(b),profile,s,top);
                check(cut.quads.size()<(rail.quads.size()*3),"the "+tag+" flangeway cut exploded the section ("
                    +rail.quads.size()+" -> "+cut.quads.size()+" faces)");
                for(double z=-1.2;z<=1.2;z+=.002){
                    boolean hit=steelAt(cut,new V3(sign*offset,0,z),top);
                    double away=Math.abs(Math.abs(z)-centre);
                    samples++;
                    if(away<=gap/2-.004){if(hit){inside++;blocked.add((sign<0?"-":"+")+"z="+Math.round(z*1000)/1000D+" away="+round(away));}}
                    else if(away>gap/2+.012){if(!hit)missing++;else outside++;}
                }
            }
            check(inside==0,"the "+tag+" cutter left "+inside+" shaved samples inside the "+gap+" m flange way");
            check(missing==0,"the "+tag+" cutter removed "+missing+" samples outside the flange way: the running rail is over-cut");
            check(outside>200,"the "+tag+" running rail outside the flange way has too little steel ("+outside+" samples)");
            check(samples>1000,"the "+tag+" flange sample count is not real ("+samples+")");
            System.out.println("     flange cut ("+tag+"): "+samples+" samples, flange way "+round(gap)
                +" m at |z|="+round(centre)+", "+inside+" blocked inside, "+missing
                +" missing outside, "+outside+" continuous outside");
            System.out.println("     [debug] blocked inside: "+blocked);
        }
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        System.out.println("PASS: a crossing road at the same height opens only its own flange way in the steel it crosses"
            +" (nothing left inside the gap, nothing removed outside it) for the built-in and the native model section");
    }

    /** Drawn steel at a running-rail cross-section: a face at the head's top height covers the point. */
    private static boolean steelAt(Mesh mesh,V3 centre,double top){
        for(var q:mesh.quads){
            if(q.part().equals("sleeper")||q.part().equals("fastener"))continue;
            if(Math.abs(q.a().y()-top)>.004&&Math.abs(q.b().y()-top)>.004&&Math.abs(q.c().y()-top)>.004&&Math.abs(q.d().y()-top)>.004)continue;
            if(triangle(q.a(),q.b(),q.c(),centre)||triangle(q.a(),q.c(),q.d(),centre))return true;
        }
        return false;
    }
    private static boolean triangle(V3 a,V3 b,V3 c,V3 p){
        double area=(b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x());
        if(Math.abs(area)<1e-12)return false;
        double d1=((b.x()-a.x())*(p.z()-a.z())-(b.z()-a.z())*(p.x()-a.x()))/area;
        double d2=((c.x()-b.x())*(p.z()-b.z())-(c.z()-b.z())*(p.x()-b.x()))/area;
        double d3=((a.x()-c.x())*(p.z()-c.z())-(a.z()-c.z())*(p.x()-c.x()))/area;
        return d1>=-1e-4&&d2>=-1e-4&&d3>=-1e-4;
    }
    private static double round(double v){return Math.round(v*1e4)/1e4D;}

    /** The same native model section the cap regression uses: an extruded I-beam in model units. */
    static Profile modelled(){
        double top=Profile.STANDARD.top(),base=top-Profile.STANDARD.railHeight();
        double foot=Profile.STANDARD.footWidth()/2,web=.011,head=Profile.STANDARD.headWidth()/2;
        double footTop=base+.025,headBottom=top-.036;
        double[][] outline={
            {-foot,base},{foot,base},{foot,footTop},{web,footTop},{web,headBottom},{head,headBottom},
            {head,top},{-head,top},{-head,headBottom},{-web,headBottom},{-web,footTop},{-foot,footTop}
        };
        var rails=new ArrayList<Mesh.Quad>();
        for(int i=0;i<outline.length;i++){
            double[] a=outline[i],b=outline[(i+1)%outline.length];
            rails.add(new Mesh.Quad(new V3(a[0],a[1],0),new V3(a[0],a[1],.24),new V3(b[0],b[1],.24),new V3(b[0],b[1],0),Profile.STEEL,"rail",-1));
        }
        // A real model rail is a closed section: the head top is the face the flangeway has to open.
        rails.add(new Mesh.Quad(new V3(-head,top,0),new V3(-head,top,.24),new V3(head,top,.24),new V3(head,top,0),Profile.STEEL,"rail",-1));
        rails.add(new Mesh.Quad(new V3(-foot,base,0),new V3(-foot,base,.24),new V3(foot,base,.24),new V3(foot,base,0),Profile.STEEL,"rail",-1));
        var detail=new ModelDetail(rails,List.of(),List.of(),0,top,Profile.STANDARD.headWidth(),0,.24,.12,base,false);
        return new Profile(Profile.STANDARD.gauge(),top,Profile.STANDARD.headWidth(),Profile.STANDARD.footWidth(),
            Profile.STANDARD.railHeight(),Profile.STEEL,Profile.TIMBER,"channel-fixture",false,detail);
    }
}
