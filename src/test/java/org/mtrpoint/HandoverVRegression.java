package org.mtrpoint;

public final class HandoverVRegression {
    public static void main(String[] args)throws Exception{
        if(args[0].equals("stock")){
            org.mtrpoint.client.StockApproachRegression.run();
            StockSectionSeamRegression.run();
            org.mtrpoint.client.ToeCoverageRegression.run();
            SwitchBladeRegression.run();
            FrogWingConnectionRegression.run();
        }else if(args[0].equals("v")){
            org.mtrpoint.client.VSectionIntersectionRegression.run();
            DiamondRegression.run();
            org.mtrpoint.client.ChannelCutRegression.run();
            org.mtrpoint.client.CapFaceRegression.run();
            org.mtrpoint.client.SavedWingRegression.run();
        }else throw new IllegalArgumentException(args[0]);
    }
}
