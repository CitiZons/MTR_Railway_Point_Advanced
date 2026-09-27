package org.mtrpoint.geometry;

import java.util.*;

/** Keep half a repeat interval at both endpoints; move only crowded supports. */
public final class SleeperLayout {
    public static double[] fit(List<Double> original,double length,double spacing){
        double[] result=new double[original.size()];Arrays.fill(result,Double.NaN);
        if(original.isEmpty()||length<=0)return result;
        double margin=Math.min(spacing/2,length/2),gap=spacing*.75;
        var keep=new ArrayList<Integer>();for(int i=0;i<original.size();i++)keep.add(i);
        int capacity=Math.max(1,1+(int)Math.floor((length-2*margin+1e-8)/gap));
        while(keep.size()>capacity){int remove=keep.size()-1;double closest=Double.MAX_VALUE;
            for(int i=1;i<keep.size();i++){double d=original.get(keep.get(i))-original.get(keep.get(i-1));if(d<closest){closest=d;remove=i;}}
            keep.remove(remove);}
        double previous=margin-gap;
        for(int index:keep){result[index]=Math.max(previous+gap,Math.min(length-margin,Math.max(margin,original.get(index))));previous=result[index];}
        double next=length-margin+gap;
        for(int i=keep.size()-1;i>=0;i--){int index=keep.get(i);result[index]=Math.min(result[index],next-gap);next=result[index];}
        for(int index:keep)if(Math.abs(result[index]-original.get(index))<1e-9)result[index]=original.get(index);
        return result;
    }
}
