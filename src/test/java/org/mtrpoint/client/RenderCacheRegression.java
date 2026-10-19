package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Headless checks for bounded caches, equivalent callback objects and empty render passes. */
public final class RenderCacheRegression {
    private static Mesh.Quad face(String part,double x){return new Mesh.Quad(new V3(x,0,0),new V3(x+1,0,0),new V3(x+1,0,1),new V3(x,0,1),Profile.STANDARD.steel(),part,-1);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static Mesh rect(String part,double x0,double x1,double z0,double z1){
        Mesh mesh=new Mesh();mesh.quad(new Mesh.Quad(new V3(x0,0,z0),new V3(x1,0,z0),new V3(x1,0,z1),new V3(x0,0,z1),Profile.STANDARD.steel(),part,-1));return mesh;
    }
    private static double area(List<Mesh> meshes){
        double total=0;for(Mesh mesh:meshes)for(var q:mesh.quads){
            var vertices=List.of(q.a(),q.b(),q.c(),q.d());
            for(int i=0;i<4;i++){V3 a=vertices.get(i),b=vertices.get((i+1)%4);total+=a.x()*b.z()-b.x()*a.z();}
        }return Math.abs(total)*.5;
    }
    private static void regions(){
        var beds=new RegionalRailGeometry(true);var joints=new RegionalRailGeometry(false);
        var a=rect("track_bed",15,17,15,17);var b=rect("track_bed",15.5,17.5,15.5,17.5);
        Mesh all=new Mesh();all.quads.addAll(a.quads);all.quads.addAll(b.quads);
        var output=beds.update(List.of(a,b));
        require(Math.abs(area(output)-area(List.of(SurfaceUnion.build(all))))<1e-9,"Regional union changed overlapping bed coverage at four-region corner");
        // Seam lies on a region boundary, including the original 19 mm allowed gap.
        var left=rect("rail_top",14,15.9905,1,2);var right=rect("rail_top",16.0095,18,1,2);
        all=new Mesh();all.quads.addAll(left.quads);all.quads.addAll(right.quads);
        var bridges=joints.update(List.of(left,right));
        double expected=area(List.of(RailJoints.bridges(all,true)));
        require(expected>0&&Math.abs(area(bridges)-expected)<1e-9,"Regional seam missing or duplicated at boundary");
        var far=rect("track_bed",161,162,1,2);beds.update(List.of(a,b,far));
        long before=beds.builds;var replacement=rect("track_bed",161,163,1,2);
        var changed=beds.update(List.of(a,b,replacement));
        require(beds.builds-before==1,"Local bed edit rebuilt unrelated regions");
        for(Mesh unchanged:output)require(changed.stream().anyMatch(mesh->mesh==unchanged),"Remote bed edit replaced stable GPU source");
        beds.clear();joints.clear();
        // Turnout assemblies can replace a single large source Mesh after a local edit.
        Mesh assembly=new Mesh();assembly.quads.addAll(a.quads);assembly.quads.addAll(far.quads);
        beds.update(List.of(assembly));before=beds.builds;
        Mesh edited=new Mesh();edited.quads.addAll(a.quads);edited.quads.addAll(replacement.quads);
        beds.update(List.of(edited));
        require(beds.builds-before==1,"Replaced assembly invalidated unchanged bed regions");
        joints.update(List.of(assembly));before=joints.builds;joints.update(List.of(edited));
        require(joints.builds-before==1,"Replaced assembly invalidated unchanged seam regions");
        beds.clear();joints.clear();
        // Simulated 60 Hz forward/back travel changes a complete spatial window, not camera yaw.
        var line=new ArrayList<Mesh>();for(int i=-8;i<64;i++)line.add(rect("track_bed",i*8+.1,i*8+7.9,1,2));
        for(double speed:new double[]{0,1,4,10,20,40}){
            beds.clear();joints.clear();List<Mesh> previous=List.of();
            for(int frame=0;frame<=1200;frame++){
                double position=speed*(frame<=600?frame:1200-frame)/60.;
                var visible=new ArrayList<Mesh>();for(Mesh mesh:line)if(Math.abs(mesh.quads.get(0).center().x()-position)<32)visible.add(mesh);
                long bedBefore=beds.builds,jointBefore=joints.builds;
                var local=beds.update(visible);joints.update(local);
                if(frame>0){
                    require(beds.builds-bedBefore<=2,"Movement rebuilt distant beds at "+speed+" m/s");
                    require(joints.builds-jointBefore<=4,"Movement rebuilt distant seams at "+speed+" m/s");
                    if(visible.equals(previous))require(beds.builds==bedBefore&&joints.builds==jointBefore,"Stable movement window rebuilt geometry");
                }
                require(Math.abs(area(local)-visible.size()*7.8)<1e-7,"Moving window lost or retained bed geometry");previous=visible;
            }
        }
        beds.clear();joints.clear();
    }
    public static void run()throws Exception{
        regions();
        residency();
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
        System.out.println("RENDER_CACHE: PASS regional union/seams, local invalidation, 0/1/4/10 m/s return coverage, stable callbacks, bounded splits, empty frames and assembly edits");
    }
    private static void residency(){
        RailCellCache.clear();Mesh steel=new Mesh();
        var sweep=new RailSweep(new V3(2,0,2),new V3(2,0,3),new V3(0,0,1),new V3(0,0,1),0,0);
        var high=List.of(face("other",0),face("track_bed",2));
        var low=List.of(face("other",.1),face("track_bed",2.1));
        var a=RailCellCache.capture("resident",()->RailCellCache.submit(high,sweep,false,0,0,1,V3.ZERO,true));
        var b=RailCellCache.capture("resident",()->RailCellCache.submit(low,sweep,false,0,0,1,V3.ZERO,true));
        RailCellCache.begin();a.submit();RailCellCache.finish(steel);
        require(RailCellCache.ready(new V3(2,0,2.5),"resident"),"Resident owner missing from committed scene");
        require(!RailCellCache.ready(new V3(2,0,2.5),"new-rail"),"A new rail inherited another rail's readiness");
        RailCellCache.begin();b.submit();RailCellCache.finish(steel);
        long builds=RailCellCache.builds,vertices=RailCellCache.transformedVertices,joints=RailCellCache.jointBuilds();
        RailCellCache.begin();a.submit();RailCellCache.finish(steel);
        require(RailCellCache.builds==builds&&RailCellCache.transformedVertices==vertices&&RailCellCache.jointBuilds()==joints,"Returning LOD rebuilt resident geometry");
        for(int frame=0;frame<120;frame++){RailCellCache.begin();RailCellCache.finish(steel);}
        require(RailCellCache.activeCount()==0,"Inactive resident rail was drawn");
        RailCellCache.begin();a.submit();RailCellCache.finish(steel);
        require(RailCellCache.builds==builds&&RailCellCache.transformedVertices==vertices,"Resident cache was evicted by frame count or absence");
        RailCellCache.invalidate(new V3(2,0,2.5),"resident",true);
        require(!RailCellCache.ready(new V3(2,0,2.5),"resident"),"Edited geometry left a stale committed replacement");
        RailCellCache.clear();
    }
}
