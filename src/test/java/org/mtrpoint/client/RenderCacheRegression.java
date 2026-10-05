package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Headless checks for bounded caches, equivalent callback objects and empty render passes. */
public final class RenderCacheRegression {
    private static Mesh.Quad face(String part,double x){return new Mesh.Quad(new V3(x,0,0),new V3(x+1,0,0),new V3(x+1,0,1),new V3(x,0,1),Profile.STANDARD.steel(),part,-1);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void run()throws Exception{
        RailCellCache.clear();
        var faces=List.of(face("track_bed",0),face("other",2));
        var split=RailCellCache.split(faces);
        require(split.beds().size()==1&&split.other().size()==1,"Face partition changed");
        require(RailCellCache.split(faces)==split,"Stable resource faces must reuse split");
        var steel=new Mesh();
        for(int i=0;i<200;i++){
            RailCellCache.begin();
            // Native callbacks can produce fresh but value-equal sweeps each frame.
            var sweep=new RailSweep(V3.ZERO,new V3(0,0,1),new V3(0,0,1),new V3(0,0,1),0,0);
            RailCellCache.submit(faces,sweep,false,0,0,1,V3.ZERO,true);
            RailCellCache.finish(steel);
        }
        require(RailCellCache.builds==2,"Unchanged callbacks rebuilt geometry: "+RailCellCache.builds);
        RailCellCache.begin();RailCellCache.finish(steel);
        require(RailCellCache.activeCount()==0,"Empty render pass retained previous cells");
        for(int i=0;i<10000;i++)RailCellCache.split(List.of(face("other",i)));
        require(RailCellCache.splitCount()<=256&&RailCellCache.splitFaceCount()<=65536,"Split cache grew beyond bounds");
        // Exercise the real assembly cache across leaving and returning to the takeover window.
        PointRenderer.clear();
        var roads=org.mtrpoint.Regression.y();var junction=Detector.find(roads).get(0);
        var view=PointRenderer.viewsForTest(List.of(junction),PointSettings.DEFAULT,Profile.STANDARD).get(0);
        var guards=PointRenderer.class.getDeclaredMethod("guards",List.class);guards.setAccessible(true);
        var builds=PointRenderer.class.getDeclaredField("guardBuilds");builds.setAccessible(true);
        guards.invoke(null,List.of(view));long before=builds.getLong(null);
        guards.invoke(null,List.of());guards.invoke(null,List.of(view));
        require(builds.getLong(null)==before,"Returning to unchanged view recut assembly");
        view.settings=PointSettings.DEFAULT.guard(2,new PointSettings.GuardEdit(2,5,false,false,""));
        guards.invoke(null,List.of(view));
        require(builds.getLong(null)>before,"Changed settings incorrectly reused assembly");
        PointRenderer.clear();
        System.out.println("RENDER_CACHE: PASS stable callbacks build once, bounded split cache, empty frames clear, returning assemblies reuse, edits invalidate");
    }
}
