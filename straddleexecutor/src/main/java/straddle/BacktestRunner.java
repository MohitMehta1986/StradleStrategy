//package straddle;
//
//import com.zerodhatech.kiteconnect.KiteConnect;
//
//import java.io.*;
//import java.nio.file.*;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.List;
//
///**
// * BacktestRunner
// *
// * Entry point for the 90-day backtest.
// *
// * ── Usage ─────────────────────────────────────────────────────────────────
// *
// *   # 1. Set credentials (same as live trading)
// *   export KITE_API_KEY=your_key
// *   export KITE_API_SECRET=your_secret
// *   export KITE_REQUEST_TOKEN=today_token   # OR KITE_ACCESS_TOKEN
// *
// *   # 2. Run backtest
// *   java -cp straddle-algo.jar com.trading.straddle.backtest.BacktestRunner
// *
// *   # 3. Results saved to:
// *   backtest_results_YYYYMMDD_HHmmss.csv
// *   backtest_results_YYYYMMDD_HHmmss.txt  (full report)
// *
// * ── Customise parameters ──────────────────────────────────────────────────
// *   Edit the TradeConfig.Builder below to test different settings.
// *   E.g. try different SL/TP levels, delta targets, or VIX bands.
// */
//public class BacktestRunner {
//
//    public static void main(String[] args) throws Exception {
//        Logger.info("════════════════════════════════════════════════════════════");
//        Logger.info("   NIFTY Short Strangle — 90-Day Backtest");
//        Logger.info("════════════════════════════════════════════════════════════");
//
//        // ── Authenticate ──────────────────────────────────────────────────────
//        KiteSessionManager session = new KiteSessionManager();
//        KiteConnect kite = session.authenticate();
//
//        // ── Configure strategy parameters for backtesting ─────────────────────
//        // Change these to test different parameter combinations
//        TradeConfig config = new TradeConfig.Builder()
//            .vixNoTradeThreshold(20.0)   // Skip if VIX ≥ 20
//            .vixEntryTarget(15.0)        // Enter when VIX ≈ 15
//            .maxPremium(100.0)           // Both legs must be < ₹100
//            .targetDelta(0.215)          // Target delta 0.20–0.23
//            .deltaRange(0.015)
//            .maxDeltaDifference(0.03)
//            .stopLossAmount(650.0)       // Exit on ₹650 portfolio loss
//            .targetProfitAmount(1200.0)  // Exit on ₹1200 portfolio profit
//            .lots(1)
//            .build();
//
//        Logger.info("Backtest config: " + config);
//
//        // ── Run backtest ──────────────────────────────────────────────────────
//        BacktestEngine engine = new BacktestEngine(kite, config);
//        List<BacktestTrade> trades = engine.run();
//
//        // ── Analyse & print report ────────────────────────────────────────────
//        BacktestAnalyser analyser = new BacktestAnalyser(config, trades);
//        analyser.printReport();
//
//        // ── Save results to files ─────────────────────────────────────────────
//        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
//        saveCsv(trades, "backtest_results_" + timestamp + ".csv");
//        Logger.info("Results saved to backtest_results_" + timestamp + ".csv");
//    }
//
//    // ════════════════════════════════════════════════════════════════════════
//    // CSV Export
//    // ════════════════════════════════════════════════════════════════════════
//
//    private static void saveCsv(List<BacktestTrade> trades, String filename) throws IOException {
//        StringBuilder sb = new StringBuilder();
//
//        // Header
//        sb.append("Date,VIX,Spot,ATM,TradeEntered,CallStrike,PutStrike,")
//          .append("CallEntryPrice,PutEntryPrice,CallDelta,PutDelta,")
//          .append("TotalCredit,CallExitPrice,PutExitPrice,ExitTime,")
//          .append("ExitReason,Quantity,RealisedPnL,CumulativePnL\n");
//
//        double cumPnl = 0;
//        for (BacktestTrade t : trades) {
//            cumPnl += t.realisedPnl;
//            sb.append(String.format(
//                "%s,%.2f,%.2f,%.0f,%b,%.0f,%.0f,%.2f,%.2f,%.4f,%.4f,%.2f,%.2f,%.2f,%s,%s,%d,%.2f,%.2f%n",
//                t.date, t.vixAtEntry, t.spotAtEntry, t.atmStrike,
//                t.tradeEntered,
//                t.callStrike, t.putStrike,
//                t.callEntryPrice, t.putEntryPrice,
//                t.callDelta, t.putDelta,
//                t.totalCreditReceived,
//                t.callExitPrice, t.putExitPrice,
//                t.exitTime != null ? t.exitTime.toLocalTime() : "",
//                t.exitReason,
//                t.quantity,
//                t.realisedPnl,
//                cumPnl
//            ));
//        }
//
//        Files.writeString(Path.of(filename), sb.toString());
//    }
//}
