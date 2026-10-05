package org.mtrpoint;

import java.util.*;
import org.mtrpoint.geometry.*;

public final class ThreeWaySafetyRegression {
    public static void main(String[] args)throws Exception{run();ThreeWayShapeRegression.run();org.mtrpoint.client.ScissorsGeometryRegression.run();}
    private static void require(boolean pass,String why){if(!pass)throw new AssertionError(why);}
    public static void run(){
        int[][] seats={{0,1},{1,-1},{1,1},{2,-1}};
        double[][] expected={{0,1,0,1},{1,0,0,1},{1,0,1,0}};
        for(int pose=0;pose<3;pose++)for(int seat=0;seat<4;seat++)
            require(ThreeWayMesh.bladeOpen(seats[seat][0],seats[seat][1],pose*.5)==expected[pose][seat],"Three-way linkage is wrong at pose="+pose+" seat="+seat);
        for(double pose=0;pose<=1;pose+=.025)for(int pair=0;pair<2;pair++)
            require(Math.abs(ThreeWayMesh.bladeOpen(pair,1,pose)+ThreeWayMesh.bladeOpen(pair+1,-1,pose)-1)<1e-9,"Adjacent switch blades must have complementary throws");
        var roads=new ArrayList<>(Regression.y().subList(0,2));
        roads.add(Regression.line("middle","0,0,0","mid-end",V3.ZERO,new V3(0,0,30)));
        Junction j=Detector.find(roads).get(0);Profile p=Profile.STANDARD;PointSettings s=PointSettings.DEFAULT;
        double start=TurnoutFrame.start(j,PointMesh.extent(j,s)),length=9,contact=TurnoutFrame.contact(length,p,s);
        for(double pose:new double[]{0,.5,1}){
            Mesh mesh=PointMesh.build(j,s,p,pose);
            for(int[] seat:seats){
                int branch=seat[0],sign=seat[1];Track road=j.tracks().get(branch),stock=j.tracks().get(branch+sign);
                if(ThreeWayMesh.bladeOpen(branch,sign,pose)!=0)continue;
                V3 center=TurnoutFrame.seatedBlade(road,stock,start,sign,0,start,length,contact,p,s);
                V3 edge=center.add(road.tangent(start).lateral().mul(sign*p.headWidth()*TurnoutFrame.TIP_TAPER/2));
                double at=TurnoutFrame.nearestOffset(stock,edge,sign*p.centerOffset());
                V3 stockEdge=stock.at(at).add(stock.tangent(at).lateral().mul(sign*(p.centerOffset()-p.headWidth()/2)));
                require(edge.distance(stockEdge)<.001,"Three-way closed tip missed stock head at pose="+pose+" branch="+branch+" sign="+sign);
            }
            require(!mesh.quads.isEmpty(),"Three-way mesh disappeared");
        }
        require(GeometryEligibility.supported(j),"Ordinary three-way incorrectly fell back");
        Track a=Regression.y().get(0),b=Regression.y().get(1);
        var raised=b.points.stream().map(v->v.add(0,v.z()*.03,0)).toList();
        Junction grade=new Junction("bad-grade",Junction.Kind.Y,a,new Track("raised",b.startNode,b.endNode,raised),V3.ZERO,0,0,20);
        require(!GeometryEligibility.supported(grade),"Non-coplanar switch must keep native rendering");
        Track folded=new Track("folded",a.startNode,"fold-end",List.of(V3.ZERO,new V3(0,0,10),new V3(3,0,2)));
        require(!GeometryEligibility.supported(new Junction("fold",Junction.Kind.Y,a,folded,V3.ZERO,0,0,16)),"Folded-back turnout must keep native rendering");
        var dense=new ArrayList<Track>();
        for(int i=0;i<2100;i++)dense.add(new Track("dense-"+i,"dense-start","dense-end",List.of(V3.ZERO,new V3(1,0,1))));
        require(Detector.find(dense).isEmpty(),"Overloaded detection must claim no native cells");
        System.out.println("THREE_WAY_SAFETY: PASS extreme/centre linkage, complementary animation, closed tips; native fallback for folded/graded/overloaded layouts");
    }
}
