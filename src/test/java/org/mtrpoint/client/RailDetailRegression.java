package org.mtrpoint.client;

public final class RailDetailRegression {
    private static void check(RailDetailDistances distances,double metres,int expected){
        if(distances.level(metres*metres)!=expected)throw new AssertionError("Unexpected detail at "+metres+" m: "+distances);
    }
    public static void run(){
        var defaults=RailDetailDistances.metres(4,12);
        check(defaults,0,0);check(defaults,3.999,0);check(defaults,4,1);check(defaults,11.999,1);check(defaults,12,2);
        var custom=RailDetailDistances.metres(8.5,30);
        check(custom,8.499,0);check(custom,8.5,1);check(custom,29.999,1);check(custom,30,2);
        var equal=RailDetailDistances.metres(6,6);
        check(equal,0,0);check(equal,5.999,0);check(equal,6,2);check(equal,100,2);
        var zero=RailDetailDistances.metres(0,0);
        check(zero,0,2);check(zero,.001,2);check(zero,4096,2);
        var noHigh=RailDetailDistances.metres(0,12);
        check(noHigh,0,1);check(noHigh,11.999,1);check(noHigh,12,2);
        // A manually edited config with reversed boundaries conservatively skips medium detail.
        check(RailDetailDistances.metres(12,4),11.999,0);check(RailDetailDistances.metres(12,4),12,2);
        System.out.println("RAIL_DETAIL: PASS default/custom/exact boundaries, equal distances, both zero and zero high band");
    }
}
