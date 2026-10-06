package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;

public final class OrdinaryRailCacheRegression {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void run()throws Exception{
        var cells=new ArrayList<RailSampler.Cell>();
        for(int i=0;i<40;i++)cells.add(new RailSampler.Cell(new V3(6+i*.6,0,0),new V3(6+(i+1)*.6,0,0)));
        var parts=OrdinaryRailCache.partition(cells);
        require(parts.values().stream().mapToInt(List::size).sum()==40,"Whole-rail partition lost cells");
        require(parts.values().stream().flatMap(List::stream).filter(OrdinaryRailCache.IndexedCell::first).count()==1,"Guard start has multiple owners");
        require(parts.values().stream().flatMap(List::stream).filter(OrdinaryRailCache.IndexedCell::last).count()==1,"Guard end has multiple owners");
        require(OrdinaryRailCache.Chunk.of(new V3(-.1,0,0)).x()==-1,"Negative chunk coordinate truncated");
        RailCellCache.clear();
        var profileA=new ProfileModel(null,List.of(),List.of(),List.of(),false,null);
        var profileB=new ProfileModel(null,List.of(),List.of(),List.of(),false,null);
        var segments=parts.values().stream().map(OrdinaryRailCache.Segment::new).toList();
        int[] captures={0};Mesh turnout=new Mesh();
        var face=new Mesh.Quad(V3.ZERO,new V3(1,0,0),new V3(1,0,1),new V3(0,0,1),Profile.STANDARD.steel(),"other",-1);
        java.util.function.Consumer<OrdinaryRailCache.Segment> build=segment->{
            captures[0]++;
            for(var cell:segment.cells){var c=cell.cell();var sweep=new RailSweep(c.a(),c.b(),new V3(1,0,0),new V3(1,0,0),0,0);
                RailCellCache.submit(List.of(face),sweep,false,0,0,1,V3.ZERO,false);}
        };
        long firstBuilds=0,firstVertices=0;
        for(int frame=0;frame<200;frame++){
            RailCellCache.begin();
            for(var segment:segments)segment.submit(profileA,List.of("native"),null,()->build.accept(segment));
            RailCellCache.finish(turnout);
            if(frame==0){firstBuilds=RailCellCache.builds;firstVertices=RailCellCache.transformedVertices;}
        }
        require(captures[0]==segments.size(),"Stationary/yaw-only frames recaptured geometry");
        require(RailCellCache.builds==firstBuilds&&RailCellCache.transformedVertices==firstVertices,"Stable full chunks rebuilt/upload input");
        var changed=segments.get(0);
        RailCellCache.begin();changed.submit(profileB,List.of("native"),null,()->build.accept(changed));
        require(captures[0]==segments.size()+1,"LOD replacement did not recapture");
        RailCellCache.begin();changed.submit(profileB,List.of("turnout"),null,()->build.accept(changed));
        require(captures[0]==segments.size()+2,"Takeover ownership did not recapture");
        changed.release();RailCellCache.begin();changed.submit(profileB,List.of("turnout"),null,()->build.accept(changed));
        require(captures[0]==segments.size()+3,"Evicted chunk did not restore on return");
        RailCellCache.begin();RailCellCache.finish(turnout);
        require(RailCellCache.activeCount()==0,"Deleted rail remained active");
        RailCellCache.clear();
        aliasModels(profileA,profileB);
        require(OrdinaryRailCache.containsStyle(List.of("cache_test_2"),"cache_test",true),"Reversed style not retained");
        require(!OrdinaryRailCache.containsStyle(List.of("cache_test_2"),"cache_test",false),"Direction edit retained the old unflipped mesh");
        require(!OrdinaryRailCache.containsStyle(List.of("other"),"cache_test",false),"Material edit retained the old mesh");
        normals();
        retainedSamples();
        RailCellCache.clear();
        System.out.println("ORDINARY_CACHE: PASS complete chunk coverage, 200 stable frames without transforms, LOD/ownership refresh, return/removal, direction aliases and support normals");
    }
    private static void aliasModels(ProfileModel high,ProfileModel far)throws Exception{
        var field=RailLod.class.getDeclaredField("LEVELS");field.setAccessible(true);
        @SuppressWarnings("unchecked") var levels=(Map<String,Object>)field.get(null);
        var type=Class.forName("org.mtrpoint.client.RailLod$Levels");
        var constructor=type.getDeclaredConstructor(ProfileModel[].class);constructor.setAccessible(true);
        ProfileModel[] models={high,high,far};levels.put("cache_test",constructor.newInstance((Object)models));
        try{
            for(String id:List.of("cache_test","cache_test_1","cache_test_2"))
                require(RailLod.registeredModels(id)[2]==far,"Direction alias selected high model: "+id);
        }finally{levels.remove("cache_test");}
    }
    private static void normals()throws Exception{
        String obj="v 0 0 0\nv 1 0 0\nv 1 0 1\nv 0 0 1\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nvn 0 1 0\nvn .2 1 0\ng support\nf 1/1/1 2/2/2 3/3/2 4/4/1\n";
        var source=ObjTemplate.read("test.obj","test.png",false,id->obj).select(List.of("support"));
        require(source.get(0).normals()!=null&&source.get(0).normals().size()==4,"OBJ smoothing normals discarded");
        var frame=new RailSweep(V3.ZERO,new V3(0,.2,1),new V3(0,.2,1).unit(),new V3(0,.2,1).unit(),.15,.15);
        RailCellCache.begin();
        RailCellCache.capture(()->RailCellCache.submit(source,frame,true,0,0,1,V3.ZERO,false)).submit();
        RailCellCache.finish(new Mesh());
        var cached=RailCellCache.class.getDeclaredField("CACHED");cached.setAccessible(true);
        Object batch=((Map<?,?>)cached.get(null)).values().iterator().next();
        var meshField=batch.getClass().getDeclaredField("mesh");meshField.setAccessible(true);Mesh mesh=(Mesh)meshField.get(batch);
        var result=mesh.quads.get(0).normals();require(result!=null,"Cached support lost normals");
        for(int i=0;i<4;i++){
            V3 expected=frame.rigid(source.get(0).normals().get(i),true,0).sub(frame.rigid(V3.ZERO,true,0)).unit();
            require(result.get(i).distance(expected)<1e-10,"Support normal disagrees with flipped/banked frame");
        }
    }
    private static void retainedSamples()throws Exception{
        var entryField=OrdinaryRailCache.class.getDeclaredField("ENTRIES");entryField.setAccessible(true);
        @SuppressWarnings("unchecked") var entries=(Map<Object,Object>)entryField.get(null);
        var keyType=Class.forName("org.mtrpoint.client.OrdinaryRailCache$Key");
        var keyConstructor=keyType.getDeclaredConstructor(String.class,String.class,boolean.class);keyConstructor.setAccessible(true);
        Object key=keyConstructor.newInstance("outside_detector","cache_test",false);entries.put(key,null);
        var samplesField=RailSampler.class.getDeclaredField("SAMPLES");samplesField.setAccessible(true);
        @SuppressWarnings("unchecked") var samples=(Map<String,Object>)samplesField.get(null);
        var sampleType=Class.forName("org.mtrpoint.client.RailSampler$Sample");var sampleConstructor=sampleType.getDeclaredConstructors()[0];sampleConstructor.setAccessible(true);
        var track=new Track("outside_detector","a","b",List.of(V3.ZERO,new V3(0,0,10)));
        Object sample=sampleConstructor.newInstance(track,List.of(),null,null,null,null);
        samples.put(track.id,sample);
        try{
            RailSampler.retain(Set.of("detector"));
            require(samples.get(track.id)==sample,"Small takeover distance evicted ordinary render samples");
            entries.remove(key);RailSampler.retain(Set.of("detector"));
            require(!samples.containsKey(track.id),"Released ordinary samples stayed pinned");
        }finally{entries.remove(key);samples.remove(track.id);}
    }

}
