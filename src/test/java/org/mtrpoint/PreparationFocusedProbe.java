package org.mtrpoint;

/** Focused geometry and scheduling checks for cold-start preparation, without the slow catalogue scan. */
public final class PreparationFocusedProbe {
    public static void main(String[] args)throws Exception{
        org.mtrpoint.client.GeometryJobsRegression.run();
        org.mtrpoint.client.AsyncViewRegression.run();
        FrameReuseRegression.run();
        ResourceModelRegression.run();
        org.mtrpoint.client.PointPreparationRegression.run();
        org.mtrpoint.client.PointTakeoverStageRegression.run();
    }
}
