package org.mtrpoint.geometry;

import java.util.*;

/** The real horizontal layers of one running-rail section. A crossing cut is resolved layer by
 * layer, because the foot, web and head of the fixed rail meet the other rail at different
 * stations once the two swept sections are intersected instead of clipped by one plane. */
public final class RailSection {
    /** One horizontal layer: full lateral span in the rail's own frame and its vertical range. */
    public record Band(double left,double right,double bottom,double top) {
        public double width(){return right-left;}
    }
    /** A built-in layer is a plain rectangle; a custom model keeps the outline of its own section. */
    public record Layer(Band band,List<double[]> outline) {
        public Layer(Band band){this(band,List.of());}
        public Layer{outline=outline==null?List.of():List.copyOf(outline);}
    }
    /** Longest station range a single conflict search may cover before its step is widened. */
    private static final int MAX_SAMPLES=4096;
    private RailSection(){}

    /** Real solid overlap of two sections. Longitudinal overlap alone is not enough: a foot
     * never conflicts with a head that is clear above it, which is what separates the layers. */
    public static boolean overlaps(Band a,Band b){
        return Math.min(a.right,b.right)-Math.max(a.left,b.left)>1e-6
            && Math.min(a.top,b.top)-Math.max(a.bottom,b.bottom)>1e-6;
    }

    /** The built-in three-part rail, or the active custom model's own sectional layers. */
    public static List<Layer> of(Profile raw,PointSettings settings){
        Profile profile=raw.tune(settings);
        double top=profile.top()+settings.verticalOffset(),base=top-profile.railHeight();
        ModelDetail detail=profile.detail();
        if(detail==null||detail.rails().isEmpty())return beams(profile,top,base);
        double scale=profile.headWidth()/detail.headWidth();
        var levels=new TreeMap<Double,List<double[]>>();
        for(var quad:detail.rails())for(V3 vertex:List.of(quad.a(),quad.b(),quad.c(),quad.d())){
            double y=Math.round((vertex.y()-detail.railTop()+top)*1e6)/1e6;
            levels.computeIfAbsent(y,k->new ArrayList<>()).add(new double[]{(vertex.x()-detail.railCenter())*scale,y});
        }
        var heights=new ArrayList<>(levels.keySet());var result=new ArrayList<Layer>();
        for(int i=1;i<heights.size();i++){
            double bottom=heights.get(i-1),upper=heights.get(i);
            if(upper-bottom<1e-6)continue;
            var outline=new ArrayList<double[]>();outline.addAll(levels.get(bottom));outline.addAll(levels.get(upper));
            double left=Double.MAX_VALUE,right=-Double.MAX_VALUE;
            for(double[] point:outline){left=Math.min(left,point[0]);right=Math.max(right,point[0]);}
            if(right-left<1e-6||outline.size()<3)continue;
            result.add(new Layer(new Band(left,right,bottom,upper),outline));
        }
        return result.isEmpty()?beams(profile,top,base):List.copyOf(result);
    }

    private static List<Layer> beams(Profile profile,double top,double base){
        return List.of(
            new Layer(new Band(-profile.footWidth()/2,profile.footWidth()/2,base,base+.025)),
            new Layer(new Band(-.011,.011,base+.025,top-.036)),
            new Layer(new Band(-profile.headWidth()/2,profile.headWidth()/2,top-.036,top)));
    }

    /** The single real layer a face's own section follows, or -1 when its span crosses a layer edge. */
    public static int face(List<Layer> layers,double bottom,double upper){
        for(int i=0;i<layers.size();i++){Band band=layers.get(i).band();if(bottom>=band.bottom()-1e-6&&upper<=band.top()+1e-6)return i;}
        return -1;
    }
    /** The layer containing a section height; the last one wins on a shared edge. */
    public static int height(List<Layer> layers,double y){
        int found=-1;
        for(int i=0;i<layers.size();i++){Band band=layers.get(i).band();if(y>=band.bottom()-1e-9&&y<=band.top()+1e-9)found=i;}
        return found;
    }

    /** Stations inside [lo,hi] where this layer is occupied by the cutter rail's own material.
     * Sampled across the crossing and refined by bisection, so every boundary is the real
     * intersection of the two swept sections rather than a station taken from one outer edge. */
    public static List<double[]> conflicts(Track road,double offset,Layer layer,List<Layer> cutterLayers,
                                          Track cutter,double cutterOffset,double cutterStart,double cutterEnd,double lo,double hi){
        var result=new ArrayList<double[]>();
        Band band=layer.band();
        if(!(hi>lo+1e-9)||band.width()<1e-9||cutterLayers.isEmpty())return result;
        double step=Math.max(.002,Math.min(.03,(hi-lo)/512));
        if((hi-lo)/step>MAX_SAMPLES)step=(hi-lo)/MAX_SAMPLES;
        boolean inside=hit(road,offset,band,cutterLayers,cutter,cutterOffset,cutterStart,cutterEnd,lo);
        double from=lo;
        for(double at=lo+step;at<hi+step*.5;at+=step){
            double station=Math.min(hi,at);
            boolean next=hit(road,offset,band,cutterLayers,cutter,cutterOffset,cutterStart,cutterEnd,station);
            if(next!=inside){
                double edge=bisect(road,offset,band,cutterLayers,cutter,cutterOffset,cutterStart,cutterEnd,from,inside,station,next);
                if(inside)result.add(new double[]{from,edge});else from=edge;
                inside=next;
            }
            if(!inside)from=station;
            if(station>=hi)break;
        }
        if(inside)result.add(new double[]{from,hi});
        return result;
    }

    /** Bisect a state change between two sampled stations. */
    private static double bisect(Track road,double offset,Band band,List<Layer> cutterLayers,Track cutter,double cutterOffset,double cutterStart,double cutterEnd,
                                 double first,boolean firstInside,double second,boolean secondInside){
        double outside=firstInside?second:first,inside=firstInside?first:second;
        if(outside==inside)return first;
        for(int i=0;i<40;i++){
            double middle=(outside+inside)/2;
            if(hit(road,offset,band,cutterLayers,cutter,cutterOffset,cutterStart,cutterEnd,middle))inside=middle;else outside=middle;
        }
        return (outside+inside)/2;
    }

    private static boolean hit(Track road,double offset,Band band,List<Layer> cutterLayers,Track cutter,double cutterOffset,double cutterStart,double cutterEnd,double station){
        V3 normal=horizontal(road.tangent(station));
        V3 centre=road.at(station).add(normal.lateral().mul(offset));
        double at=Math.max(cutterStart,Math.min(cutterEnd,cutter.nearest(centre)));
        V3 cutterNormal=horizontal(cutter.tangent(at)).lateral();
        V3 base=cutter.at(at).add(cutterNormal.mul(cutterOffset));
        V3 a=centre.add(normal.lateral().mul(band.left())),b=centre.add(normal.lateral().mul(band.right()));
        for(Layer other:cutterLayers){
            if(!overlaps(band,other.band()))continue;
            V3 c=base.add(cutterNormal.mul(other.band().left())),d=base.add(cutterNormal.mul(other.band().right()));
            if(crossing(a,b,c,d))return true;
        }
        return false;
    }

    /** True only when the two horizontal segments really meet in plan. */
    private static boolean crossing(V3 a,V3 b,V3 c,V3 d){
        double ux=b.x()-a.x(),uz=b.z()-a.z(),vx=d.x()-c.x(),vz=d.z()-c.z();
        double denominator=ux*vz-uz*vx;
        if(Math.abs(denominator)<1e-12)return false;
        double wx=c.x()-a.x(),wz=c.z()-a.z();
        double s=(wx*vz-wz*vx)/denominator,t=(wx*uz-wz*ux)/denominator;
        return s>=-1e-9&&s<=1+1e-9&&t>=-1e-9&&t<=1+1e-9;
    }

    private static V3 horizontal(V3 value){return new V3(value.x(),0,value.z()).unit();}
}
