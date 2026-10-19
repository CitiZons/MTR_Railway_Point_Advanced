package org.mtrpoint;

import org.mtrpoint.geometry.*;
import java.util.*;

/** Exact raw-frame parity check for static geometry reuse across turnout animation poses. */
public final class FrameReuseRegression {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){run();}
    public static void run(){
        profile(Profile.STANDARD);
        System.out.println("FRAME_REUSE: PASS exact raw Y and three-way frames for fixed/movable frogs");
    }
    /** Run the focused parity probe for each built-in or resource-pack profile. */
    public static void profile(Profile profile){
        Junction y=Detector.find(Regression.y()).stream().filter(j->j.kind()==Junction.Kind.Y).findFirst().orElseThrow();
        Junction three=ThreeWayShapeRegression.common();
        for(Junction junction:List.of(y,three)){
            for(boolean movable:new boolean[]{false,true}){
                PointSettings settings=PointSettings.DEFAULT.flags(movable,true);
                PointMesh.YBoundary boundary=PointMesh.YBoundary.nominal(junction,settings).window(.35,.2,.1);
                Mesh.FrameReuse reuse=new Mesh.FrameReuse();int moving=-1;Mesh first=null;
                for(double pose:new double[]{0,.5,1}){
                    Mesh expected=PointMesh.build(junction,settings,profile,pose,boundary);
                    Mesh actual=PointMesh.build(junction,settings,profile,pose,boundary,reuse);
                    require(expected.quads.equals(actual.quads),profile.source()+" "+junction.kind()+" movable="+movable+" raw frame differs at pose "+pose+": expected "+expected.quads.size()+", got "+actual.quads.size());
                    if(moving<0){moving=reuse.movingFaceCount();first=actual;}
                    require(reuse.movingFaceCount()==moving,"Moving topology changed during animation");
                    if(pose>.0){
                        var identities=Collections.newSetFromMap(new IdentityHashMap<Mesh.Quad,Boolean>());identities.addAll(actual.quads);
                        long shared=first.quads.stream().filter(identities::contains).count();
                        long expectedStatic=first.quads.size()-moving;
                        require(shared==expectedStatic,profile.source()+" "+junction.kind()+" movable="+movable+" reused "+shared+" static quad identities; expected "+expectedStatic);
                    }
                }
                require(moving>0,junction.kind()+" movable="+movable+" did not expose reusable moving geometry");
            }
        }
    }
}
