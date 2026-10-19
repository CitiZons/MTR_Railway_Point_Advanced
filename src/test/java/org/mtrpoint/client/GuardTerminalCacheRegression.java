package org.mtrpoint.client;

import org.mtrpoint.geometry.*;
import java.util.*;

public final class GuardTerminalCacheRegression {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void run(){
        Track track=new Track("terminal","a","b",List.of(new V3(0,0,7.8),new V3(0,0,24.2)));
        var cells=new ArrayList<RailSampler.Cell>();for(int i=0;i<28;i++){double from=7.8+i*.6,to=Math.min(24.2,from+.6);if(to>from)cells.add(new RailSampler.Cell(new V3(0,0,from),new V3(0,0,to)));}
        var partition=OrdinaryRailCache.partition(cells);var ends=new ContinuousGuards.Ends(true,true);
        var corrected=new RailSweep(new V3(0,0,7.7),new V3(0,0,8.5),new V3(0,0,1),new V3(0,0,1),0,0);
        var rawOwner=new OrdinaryRailCache.Chunk(0,0,0);
        var ownership=List.of(new GuardTerminalCache.NativeCell(corrected,0,.6,rawOwner),new GuardTerminalCache.NativeCell(new RailSweep(new V3(0,0,8.5),new V3(0,0,9.1),new V3(0,0,1),new V3(0,0,1),0,0),.6,1.3,new OrdinaryRailCache.Chunk(0,0,1)));
        require(GuardTerminalCache.nativeChunk(track,ownership,corrected).equals(rawOwner),"Adjusted sweep midpoint overrode the raw callback chunk");
        var terminalSlice=new RailSweep(new V3(0,0,8.6),new V3(0,0,8.9),new V3(0,0,1),new V3(0,0,1),0,0);
        require(GuardTerminalCache.nativeChunk(track,ownership,terminalSlice).equals(new OrdinaryRailCache.Chunk(0,0,1)),"Extra terminal slice was assigned to the wrong raw cell station");
        require(GuardTerminalCache.terminalSegment(track,partition.get(new OrdinaryRailCache.Chunk(0,0,1)),1.8,ends),"Endpoint reach crosses the 8 m boundary but neighbour segment was omitted");
        require(!GuardTerminalCache.terminalSegment(track,partition.get(new OrdinaryRailCache.Chunk(0,0,1)),1.8,new ContinuousGuards.Ends(false,false)),"Joined guard ends acquired a false terminal");
        var contributions=new ArrayList<RailCellCache.Contribution>();var selected=new LinkedHashSet<OrdinaryRailCache.Chunk>();
        var face=new Mesh.Quad(V3.ZERO,new V3(.2,0,0),new V3(.2,0,.6),new V3(0,0,.6),Profile.STANDARD.steel(),"guard",-1);
        for(var entry:partition.entrySet())if(GuardTerminalCache.terminalSegment(track,entry.getValue(),1.8,ends)){
            selected.add(entry.getKey());contributions.add(RailCellCache.capture("owner",()->{for(var item:entry.getValue()){var cell=item.cell();RailCellCache.submit(List.of(face),new RailSweep(cell.a(),cell.b(),new V3(0,0,1),new V3(0,0,1),0,0),false,0,0,.6,V3.ZERO,true);}}));
        }
        var prepared=RailCellCache.Contribution.worldChunks(contributions);
        require(prepared.keySet().equals(selected),"Complete endpoint cell replacement changed chunk coverage");
        require(prepared.values().stream().mapToInt(mesh->mesh.quads.size()).sum()==partition.entrySet().stream().filter(e->selected.contains(e.getKey())).mapToInt(e->e.getValue().size()).sum(),"Endpoint replacement lost or duplicated source faces");
        var profile=new ProfileModel(null,List.of(),List.of(),List.of(),false,null);
        GuardTerminalCache.update("owner",track,profile,ends,List.of("same owners"),selected,contributions);
        var key=selected.iterator().next();Mesh first=GuardTerminalCache.preparedMesh("owner",key);require(first!=null,"Terminal cache did not prepare complete cells");
        GuardTerminalCache.update("owner",track,profile,ends,List.of("same owners"),selected,List.of());
        require(first==GuardTerminalCache.preparedMesh("owner",key),"LOD/capture replacement rebuilt a valid fixed terminal snapshot");
        GuardTerminalCache.update("owner",track,profile,ends,List.of("changed owners"),selected,contributions);
        require(first!=GuardTerminalCache.preparedMesh("owner",key),"Turnout ownership edit retained stale terminal cells");
        GuardTerminalCache.retain(Set.of());require(GuardTerminalCache.preparedMesh("owner",key)==null,"Removed rail retained its terminal replacement");
        GuardTerminalCache.clear();RailCellCache.clear();
        System.out.println("GUARD_TERMINAL_CACHE: PASS complete endpoint cells, cross-chunk reach, LOD reuse, ownership invalidation and removal");
    }
}
