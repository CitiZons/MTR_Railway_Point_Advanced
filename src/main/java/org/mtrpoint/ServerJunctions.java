package org.mtrpoint;

import net.minecraft.core.BlockPos;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Called on MTR's simulation thread for one requested appearance edit. */
final class ServerJunctions {
    private ServerJunctions() {}

    static boolean valid(Simulator simulator,String id,BlockPos center){
        if(!(id.startsWith("y:")||id.startsWith("t:")||id.startsWith("x:")))return false;
        var tracks=new ArrayList<Track>();
        for(Rail rail:simulator.rails){
            var math=rail.railMath;
            if(rail.getTransportMode()!=TransportMode.TRAIN||math.getLength()<=1||
               math.minX>center.getX()+2||math.maxX<center.getX()-2||
               math.minY>center.getY()+2||math.maxY<center.getY()-2||
               math.minZ>center.getZ()+2||math.maxZ<center.getZ()-2)continue;
            Track track=sample(rail);
            if(track!=null)tracks.add(track);
            if(tracks.size()>256)return false;
        }
        return matches(Detector.find(tracks),id,center);
    }

    static boolean matches(List<Junction> junctions,String id,BlockPos center){
        return junctions.stream().anyMatch(j->j.id().equals(id)&&BlockPos.containing(j.center().x(),j.center().y(),j.center().z()).equals(center));
    }

    private static Track sample(Rail rail){
        var ends=new ObjectArraySet<Position>();rail.writePositions(ends);
        if(ends.size()!=2)return null;
        Position[] positions=ends.toArray(new Position[0]);
        var start=rail.railMath.getPosition(0,false);
        if(Math.hypot(positions[0].getX()+.5-start.x,positions[0].getZ()+.5-start.z)>
           Math.hypot(positions[1].getX()+.5-start.x,positions[1].getZ()+.5-start.z)){
            Position swap=positions[0];positions[0]=positions[1];positions[1]=swap;
        }
        var points=new ArrayList<V3>();
        rail.railMath.render((x1,z1,x2,z2,x3,z3,x4,z4,y1,y2)->{
            if(points.isEmpty())points.add(new V3(x1,y1,z1));
            points.add(new V3(x3,y2,z3));
        },.4,0,0);
        return points.size()<2?null:new Track(rail.getHexId(),node(positions[0]),node(positions[1]),points);
    }

    private static String node(Position p){return p.getX()+","+p.getY()+","+p.getZ();}
}
