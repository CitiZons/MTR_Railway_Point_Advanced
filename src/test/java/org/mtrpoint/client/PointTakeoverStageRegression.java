package org.mtrpoint.client;

import org.mtrpoint.Regression;
import org.mtrpoint.geometry.*;

/** Pure decision checks for the frame-frozen native -> local fallback -> pooled handoff. */
public final class PointTakeoverStageRegression {
    public static void run(){
        require(PointRenderer.resolveStageForTest(false,false,false,false)==0,"Unprepared or out-of-range views stay native");
        require(PointRenderer.resolveStageForTest(false,false,true,false)==0,"A ready fallback stays native outside takeover range");
        require(PointRenderer.resolveStageForTest(false,false,true,true)==1,"A ready local fallback hands off without sibling readiness");
        require(PointRenderer.resolveStageForTest(true,false,true,true)==1,"A pooled assembly outside takeover range cannot replace the local fallback");
        require(PointRenderer.resolveStageForTest(true,true,false,true)==2,"A complete pooled component supersedes fallback atomically");
        require(PointRenderer.resolveStageForTest(true,true,true,true)==2,"Pooled readiness wins over local fallback readiness");
        require(PointRenderer.resolveStageForTest(true,true,false,false)==2,"One near member activates the complete pooled component for every owner");
        clippedScissorsGuardTerminatesAtOwnedPlane();
    }
    private static void clippedScissorsGuardTerminatesAtOwnedPlane(){
        var junctions=Detector.find(Regression.scissors(false));var group=ScissorsLayout.find(junctions).get(0);var junction=group.turnouts().get(0);
        V3 a=group.crossing().center().sub(group.axis().mul(20)),b=group.crossing().center().add(group.axis().mul(20));
        Track road=Regression.line("takeover-clip","clip-a","clip-b",a,b);
        var full=new GuardRails.Run(road,0,road.length,0,false,false,Profile.STANDARD,PointSettings.DEFAULT,"","guard");
        GuardRails.Run clipped=PointRenderer.clipFallbackRun(junction,group,full);
        if(clipped==null||clipped.end()-clipped.start()>=full.end()-full.start()-1e-5)throw new AssertionError("Scissors fallback guard was not clipped to its owner's half-plane");
        boolean before=junction.center().sub(group.crossing().center()).dot(group.axis())<0;
        V3 plane=group.crossing().center().add(group.axis().mul(before?group.lo():group.hi()));V3 normal=before?group.axis():group.axis().mul(-1);
        double start=clipped.center(clipped.start()).sub(plane).dot(normal),end=clipped.center(clipped.end()).sub(plane).dot(normal);
        if(start>1e-6||end>1e-6||Math.min(Math.abs(start),Math.abs(end))>1e-5)throw new AssertionError("Fallback guard crosses or misses its scissors ownership plane");
    }
    private static void require(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
