package org.mtrpoint.client;

import org.mtrpoint.PointConfig;

final class PointUiScale {
    private PointUiScale(){}
    static double effective(int width,int height){
        return Math.max(.35,Math.min(PointConfig.uiScale(),Math.min(width/640D,height/430D)));
    }
}
