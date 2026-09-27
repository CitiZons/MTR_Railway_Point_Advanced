package org.mtrpoint;

/** Individually runnable acceptance gates for the four reported visual defects. */
public final class FourFixRegression {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "stock" -> {
                StockSectionSeamRegression.run();
                org.mtrpoint.client.ScissorsGeometryRegression.run();
                org.mtrpoint.client.StockRailIntervalRegression.run();
            }
            case "frog" -> {
                FrogWingConnectionRegression.run();
                org.mtrpoint.client.SavedWingRegression.run();
                org.mtrpoint.client.ReviewFixRegression.run();
            }
            case "rod" -> {
                StretcherHeightRegression.run();
                AssemblyRegression.run();
            }
            case "guard" -> {
                GuardMergeRegression.run();
                AssemblyRegression.run();
                CurvedTurnoutRegression.run();
                org.mtrpoint.client.ScreenshotGeometryRegression.run();
            }
            default -> throw new IllegalArgumentException(args[0]);
        }
    }
}
