package org.mtrpoint.client;

import org.mtrpoint.*;
import org.mtrpoint.geometry.*;
import java.util.*;

/** Parity checks for immutable async view snapshots against the established synchronous builder. */
public final class AsyncViewRegression {
    private AsyncViewRegression() {}
    private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}

    public static void run(){
        var s=PointSettings.DEFAULT;var p=Profile.STANDARD;
        var yRoads=Regression.y();var y=Detector.find(yRoads);
        parity("Y",yRoads,y,s,p);

        var threeRoads=new ArrayList<Track>(yRoads.subList(0,2));
        threeRoads.add(Regression.line("middle","0,0,0","0,0,30",new V3(0,0,0),new V3(0,0,30)));
        parity("three-way",threeRoads,Detector.find(threeRoads),s,p);

        var diamondRoads=List.of(
            Regression.line("diamond-east-west","-20,0,0","20,0,0",new V3(-20,0,0),new V3(20,0,0)),
            Regression.line("diamond-north-south","0,0,-20","0,0,20",new V3(0,0,-20),new V3(0,0,20)));
        parity("diamond",diamondRoads,Detector.find(diamondRoads),s,p);

        var scissorsRoads=Regression.scissors(false);var scissorsJunctions=Detector.find(scissorsRoads);
        parityViews("scissors",scissorsRoads,scissorsJunctions,s,p);
        editInvalidates(y.get(0),s,p);
        System.out.println("PASS: async view snapshots match synchronous geometry for Y, three-way, diamond and scissors; edits rebuild snapshots");
    }

    private static void parity(String name,List<Track> roads,List<Junction> junctions,PointSettings settings,Profile profile){
        parityViews(name,roads,junctions,settings,profile);
    }
    private static void parityViews(String name,List<Track> roads,List<Junction> junctions,PointSettings settings,Profile profile){
        var sync=PointRenderer.viewsForTest(junctions,settings,profile);
        var async=PointRenderer.viewsForTest(junctions,settings,profile);
        PointClient.refreshCrossings(sync,roads);PointClient.refreshCrossings(async,roads);
        require(sync.size()==async.size(),name+" view count");
        for(int i=0;i<sync.size();i++){
            var expected=sync.get(i);var actual=async.get(i);
            for(double position:new double[]{0,.5,1}){
                expected.position=position;actual.position=position;
                Mesh synchronous=expected.mesh();
                require(actual.prepareAsync(),name+" async snapshot should be ready in the headless regression runtime");
                Mesh prepared=actual.mesh();
                require(synchronous.quads.equals(prepared.quads),name+" full quad values differ at view "+expected.junction.id()+" position "+position);
            }
        }
    }
    private static void editInvalidates(Junction junction,PointSettings original,Profile profile){
        var edited=original.with(0,1.35);
        var async=new PointClient.View(junction,original,profile,Set.of("default"));
        var baseline=new PointClient.View(junction,original,profile,Set.of("default"));
        require(async.prepareAsync(),"initial async frame builds headlessly");
        async.position=0;Mesh before=async.mesh();
        async.preview(edited);async.position=0;
        require(async.prepareAsync(),"edited async frame rebuilds headlessly");
        Mesh after=async.mesh();
        baseline.preview(edited);baseline.position=0;Mesh synchronous=baseline.mesh();
        require(after.quads.equals(synchronous.quads),"edited async frame matches fresh synchronous geometry");
        require(!before.quads.equals(after.quads),"geometry edit invalidates the prepared frame result");
    }
}
