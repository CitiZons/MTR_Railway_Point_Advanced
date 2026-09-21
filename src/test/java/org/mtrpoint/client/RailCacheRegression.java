package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import org.mtrpoint.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Per-frame and per-vertex cache regressions for the two hot paths the renderer runs while a train
 * is moving fast.
 *
 * <p>RailRenderMixin asks PointClient.suppress() for every rendered rail cell, so the answer is
 * gated on the rail owning a view at all: styles are no longer normalised for rails no view covers.
 * RailSampler.bank() banks a tagged vertex in its own road's frame, so that frame is now cached by
 * (position, road id) instead of being recomputed for every face corner.
 *
 * <p>Both changes are proven as invariants: the gated query is compared against the exact
 * pre-gate implementation over a grid of rails, styles and positions, and the banked mesh is
 * compared against an independent expected transform while a counter proves how many vertices were
 * actually banked.
 */
public final class RailCacheRegression {
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}

    public static void run()throws Exception{
        suppressGate();
        bankOwnRoadCache();
        System.out.println("PASS: the suppression gate answers exactly like the pre-gate scan for every rail, style and position, and an unowned rail never normalises a style");
        System.out.println("PASS: a tagged rail vertex is banked once per (position, road) pair and every vertex still lands in its own road's frame");
    }

    private static void suppressGate(){
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        var tracks=Regression.y();
        Junction junction=Detector.find(tracks).stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        var view=new PointClient.View(junction,s,p,Set.of("default","default_3d"));
        view.mesh=PointMesh.build(junction,s,p,0);
        PointClient.installForTest(List.of(view));
        try{
            var rails=new ArrayList<>(PointClient.gatedRails());
            require(rails.size()>=2,"Suppression fixture owns no rails");
            require(rails.contains(junction.a().id)&&rails.contains(junction.b().id),"Suppression fixture does not own its own branches");
            rails.add("mtrpoint.rail-nobody-owns");
            String[] styles={"default","default_3d","default_3d_siding","minecraft:block/rail","mtrpoint.no-such-style"};
            int trueAnswers=0,samples=0;
            for(String rail:rails)for(String style:styles)for(double d=-20;d<=40;d+=2.5)for(int lateral=-2;lateral<=2;lateral++){
                V3 point=junction.a().at(Math.max(0,Math.min(junction.a().length,d))).add(junction.a().tangent(Math.max(0,Math.min(junction.a().length,d))).lateral().mul(lateral*.75));
                boolean gated=PointClient.suppressForTest(rail,style,point,0);
                boolean reference=PointClient.suppressReferenceForTest(rail,style,point,0);
                require(gated==reference,"The suppression gate changed the answer for rail "+rail+" style "+style+" at "+point+": "+gated+" vs "+reference);
                if(gated)trueAnswers++;
                samples++;
            }
            require(trueAnswers>0,"Suppression fixture never suppresses a rail cell");
            require(trueAnswers<samples,"Suppression fixture suppresses every rail cell");
            int normalised=PointClient.normalisedForTest();
            require(!PointClient.suppressForTest("mtrpoint.rail-nobody-owns","mtrpoint.never-normalised",new V3(0,0,0),0),
                "A rail no view owns was reported as owned");
            require(PointClient.normalisedForTest()==normalised,
                "A rail no view owns still normalised the style id before the ownership gate");
        }finally{PointClient.clear();}
    }

    /** A bank frame that only translates, so the expected result is a property of the mesh. */
    public static org.mtr.core.tool.Vector translate(double x,double y,double z){return new org.mtr.core.tool.Vector(x+1,y+2,z+3);}

    private static void bankOwnRoadCache()throws Exception{
        Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        var tracks=Regression.y();
        Junction junction=Detector.find(tracks).stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        Mesh mesh=PointMesh.build(junction,s,p,0);
        Track road=junction.a();
        require(mesh.quads.stream().anyMatch(q->q.rail()!=null&&q.rail().road().id.equals(road.id)),"Bank fixture has no tagged rail on its own road");
        var samplesField=RailSampler.class.getDeclaredField("SAMPLES");samplesField.setAccessible(true);
        var bankMethodField=RailSampler.class.getDeclaredField("bankMethod");bankMethodField.setAccessible(true);
        var savedBankMethod=bankMethodField.get(null);
        @SuppressWarnings("unchecked") var samples=(Map<String,Object>)samplesField.get(null);
        Object sample=null;
        try{
            Class<?> bankType=Class.forName("org.mtrpoint.client.RailSampler$Bank");
            Constructor<?> bankConstructor=bankType.getDeclaredConstructors()[0];bankConstructor.setAccessible(true);
            Object bank=bankConstructor.newInstance(0D,road.length+1,(Object)null);
            Class<?> sampleType=Class.forName("org.mtrpoint.client.RailSampler$Sample");
            Constructor<?> sampleConstructor=sampleType.getDeclaredConstructors()[0];sampleConstructor.setAccessible(true);
            sample=sampleConstructor.newInstance(road,List.of(bank),null,null,null);
            samples.put(road.id,sample);
            bankMethodField.set(null,RailCacheRegression.class.getMethod("translate",double.class,double.class,double.class));

            // Two caches, exactly as the production path: tagged vertices of a banked road use their
            // own road's frame, everything else shares the nearest-sample cache.
            var own=new HashSet<V3>();var shared=new HashSet<V3>();long ownCorners=0;
            for(var q:mesh.quads)for(V3 v:List.of(q.a(),q.b(),q.c(),q.d()))
                if(q.rail()!=null&&q.rail().road().id.equals(road.id)){own.add(v);ownCorners++;}else shared.add(v);

            long[] cached=calls(mesh,junction,List.of(road));
            Mesh banked=RailSampler.bank(mesh,junction,List.of(road));
            require(cached[0]==own.size()+shared.size(),"Tagged banking ran "+cached[0]+" times for "+own.size()+" own-road and "+shared.size()+" shared vertices");
            require(cached[0]<ownCorners+shared.size(),"The own-road bank cache never reused a vertex");
            int translated=0;
            for(int i=0;i<mesh.quads.size();i++){
                var before=mesh.quads.get(i);var after=banked.quads.get(i);
                for(int corner=0;corner<4;corner++){
                    V3 from=corner==0?before.a():corner==1?before.b():corner==2?before.c():before.d();
                    V3 to=corner==0?after.a():corner==1?after.b():corner==2?after.c():after.d();
                    require(to.distance(from.add(new V3(1,2,3)))<1e-12,"A banked vertex did not use the expected frame: "+to+" from "+from);
                    translated++;
                }
            }
            require(translated==mesh.quads.size()*4,"Banked mesh lost vertices");

            // Negative control: with the own-road cache switched off every own-road corner is banked
            // again, which is the count the assertion above would report as a failure.
            RailSampler.ownRoadCache=false;
            long[] uncached=calls(mesh,junction,List.of(road));
            RailSampler.ownRoadCache=true;
            require(uncached[0]==ownCorners+shared.size(),"Uncached banking ran "+uncached[0]+" times instead of "+ownCorners+" own-road corners plus "+shared.size()+" shared vertices");
            require(uncached[0]>cached[0],"The own-road cache did not reduce the number of banked vertices");
        }finally{
            if(sample!=null)samples.remove(road.id);
            bankMethodField.set(null,savedBankMethod);
            RailSampler.ownRoadCache=true;
        }
    }

    /** Bank the mesh once and report how many per-sample banking calls it took. */
    private static long[] calls(Mesh mesh,Junction junction,List<Track> roads){
        RailSampler.bankCalls=0;
        RailSampler.bank(mesh,junction,roads);
        return new long[]{RailSampler.bankCalls};
    }
}
