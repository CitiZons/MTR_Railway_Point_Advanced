package org.mtrpoint.client;

/** Distance policy shared by native model selection and persistent turnout batches. */
public record RailDetailDistances(double nearSquared,double farSquared) {
    public static RailDetailDistances metres(double near,double far){
        return new RailDetailDistances(near*near,Math.max(near,far)*Math.max(near,far));
    }
    public int level(double distanceSquared){
        return distanceSquared<nearSquared?0:distanceSquared<farSquared?1:2;
    }
}
