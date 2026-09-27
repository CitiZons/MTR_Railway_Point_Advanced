package org.mtrpoint;

import java.util.*;
import org.mtrpoint.geometry.*;

/** UI eligibility and the resulting world geometry must agree for independently owned runs. */
final class GuardMergeRegression {
    private static final Profile P=Profile.STANDARD;
    private static final PointSettings S=PointSettings.DEFAULT;
    private static GuardRails.Run run(Track road,double from,double to,double offset){
        // Old saved merges cleared both flags. Physical outer mouths must be restored.
        return new GuardRails.Run(road,from,to,offset,false,false,P,S,"manual-regression");
    }
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void run(){
        int mouths=0;
        for(boolean mirror:new boolean[]{false,true})for(boolean reverse:new boolean[]{false,true})for(double shift:new double[]{0,.05}){
            double sign=mirror?-1:1;
            Track a=Regression.line("merge-a","a0","a1",new V3(0,0,0),new V3(0,0,12));
            var points=new ArrayList<V3>();for(int i=0;i<=120;i++){double z=i*.1;points.add(new V3(sign*(z<6?0:.012*(z-6)*(z-6)),0,z));}
            Track b=new Track("merge-b","b0","b1",points);
            var first=run(a,2,6,sign*.6);var second=run(b,4,11,sign*(.6+shift));
            if(reverse)second=run(b.reverse(),b.length-11,b.length-4,-sign*(.6+shift));
            var pair=List.of(first,second);
            require(GuardRails.merge(pair).size()==2,"Fixture no longer exercises the rejected multi-curve merge");
            require(GuardRails.canMerge(pair),"UI rejects the continuous partial overlap");
            mouths+=checkMouths(pair);
            // Extend through a third independently selected interval; order must not matter.
            var chain=List.of(run(b,10,12,sign*(.6+shift)),first,second);
            require(GuardRails.canMerge(chain),"UI rejects a connected three-source merge");
        }
        Track a=Regression.line("left","l0","l1",new V3(0,0,0),new V3(0,0,6));
        Track b=Regression.line("right","r0","r1",new V3(0,0,6),new V3(0,0,12));
        var abutting=List.of(run(a,2,6,.6),run(b,0,4,.6));
        require(GuardRails.canMerge(abutting),"UI rejects touching endpoints with different road IDs");
        mouths+=checkMouths(abutting);
        require(!GuardRails.canMerge(List.of(run(a,1,5,.6),run(a,1,5,-.6))),"Opposite sides were merged");
        require(!GuardRails.canMerge(List.of(run(a,1,2,.6),run(a,3,5,.6))),"Separated intervals were merged");
        Track raised=Regression.line("raised","u0","u1",new V3(0,.04,0),new V3(0,.04,6));
        require(!GuardRails.canMerge(List.of(run(a,1,5,.6),run(raised,1,5,.6))),"Different world heights were merged");
        Track parallel=Regression.line("parallel","p0","p1",new V3(.3,0,0),new V3(.3,0,6));
        require(!GuardRails.canMerge(List.of(run(a,1,5,.6),run(parallel,1,5,.6))),"Separate parallel rails were merged");
        Track crossing=Regression.line("crossing","c0","c1",new V3(-3,0,3),new V3(3,0,3));
        require(!GuardRails.canMerge(List.of(run(a,1,5,.6),run(crossing,1,5,.6))),"Transverse crossing was treated as the same rail");
        System.out.println("PASS: partial/reversed/mirrored/cross-ID guard merges agree with world union; "+mouths+" outer mouths survive; internal mouths and unrelated merges rejected");
    }
    private static int checkMouths(List<GuardRails.Run> runs){
        var finished=DiamondGeometry.finishedGuards(runs,List.of());
        Mesh mesh=DiamondGeometry.guards(runs);var tops=DiamondRegression.tops(mesh,P.top());int mouths=0;
        for(var r:finished)for(boolean start:new boolean[]{true,false}){
            if(!(start?r.flareStart():r.flareEnd()))continue;mouths++;
            double end=start?r.start():r.end();
            require(Math.abs(r.point(end).distance(r.center(end))-.1)<1e-8,"Outer mouth lost its inward bend");
            for(double inset:new double[]{.04,.17,.31}){
                double d=end+(start?inset:-inset);V3 point=r.point(d);
                require(DiamondRegression.coverage(tops,point)>0,"Outer inward bend is missing from the world mesh");
            }
            double d=end+(start?.04:-.04);
            require(DiamondRegression.coverage(tops,r.center(d))==0,"A straight duplicate masks the inward mouth");
        }
        require(mouths==2,"Merged guard needs exactly two outer mouths, found "+mouths);
        for(var r:finished)for(double d=r.start()+.41;d<r.end()-.41;d+=.137){
            V3 point=r.center(d).add(r.road().tangent(d).lateral().mul(.007));
            require(DiamondRegression.coverage(tops,point)==1,"Merged guard is missing or has duplicate head faces");
        }
        return mouths;
    }
}
