package straddle;


import java.util.*;
import java.util.stream.Collectors;

/**
 * BacktestAnalyser
 *
 * Computes performance statistics from the list of BacktestTrade results
 * and prints a formatted report to console.
 *
 * Metrics computed:
 *   • Total / Win / Loss / No-trade days
 *   • Win rate, Profit factor
 *   • Total PnL, Average PnL per trade
 *   • Max single-day profit and loss
 *   • Max consecutive wins and losses
 *   • Max drawdown (peak-to-trough on cumulative PnL)
 *   • Sharpe ratio (daily returns)
 *   • Breakdown by exit reason (SL / TP / EOD)
 *   • Monthly PnL summary
 *   • Per-trade detail table
 */
public class BacktestAnalyser {

    private final TradeConfig        config;
    private final List<BacktestTrade> trades;

    // Filtered to only days where a trade was actually entered
    private final List<BacktestTrade> activeTrades;

    public BacktestAnalyser(TradeConfig config, List<BacktestTrade> trades) {
        this.config       = config;
        this.trades       = trades;
        this.activeTrades = trades.stream()
            .filter(t -> t.tradeEntered)
            .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main Report
    // ════════════════════════════════════════════════════════════════════════

    public void printReport() {
        if (trades.isEmpty()) {
            System.out.println("No trades to analyse.");
            return;
        }

        printHeader();
        printSummary();
        printExitBreakdown();
        printDrawdown();
        printStreaks();
        printMonthlyBreakdown();
        printTradeTable();
        printFooter();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Sections
    // ════════════════════════════════════════════════════════════════════════

    private void printHeader() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║         NIFTY SHORT STRANGLE — BACKTEST REPORT (90 DAYS)        ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════╣");
        System.out.printf ("║  Strategy Config: %s%n", config);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private void printSummary() {
        int totalDays   = trades.size();
        int noTradeDays = trades.size() - activeTrades.size();
        int tradeDays   = activeTrades.size();
        int wins        = (int) activeTrades.stream().filter(t -> t.realisedPnl > 0).count();
        int losses      = (int) activeTrades.stream().filter(t -> t.realisedPnl < 0).count();
        int breakEvens  = tradeDays - wins - losses;

        double totalPnl    = activeTrades.stream().mapToDouble(t -> t.realisedPnl).sum();
        double avgPnl      = tradeDays > 0 ? totalPnl / tradeDays : 0;
        double winRate     = tradeDays > 0 ? (wins * 100.0 / tradeDays) : 0;
        double totalProfit = activeTrades.stream().filter(t -> t.realisedPnl > 0).mapToDouble(t -> t.realisedPnl).sum();
        double totalLoss   = Math.abs(activeTrades.stream().filter(t -> t.realisedPnl < 0).mapToDouble(t -> t.realisedPnl).sum());
        double profitFactor = totalLoss > 0 ? totalProfit / totalLoss : Double.POSITIVE_INFINITY;
        double avgWin      = wins > 0 ? totalProfit / wins : 0;
        double avgLoss     = losses > 0 ? totalLoss / losses : 0;

        OptionalDouble maxProfit = activeTrades.stream().mapToDouble(t -> t.realisedPnl).max();
        OptionalDouble maxLoss   = activeTrades.stream().mapToDouble(t -> t.realisedPnl).min();

        double sharpe = computeSharpe();

        sep("SUMMARY");
        row("Total calendar days scanned",   totalDays);
        row("Trading days (VIX in range)",   tradeDays);
        row("No-trade days (VIX gate)",       noTradeDays);
        System.out.println();
        row("Winning days",                   wins);
        row("Losing days",                    losses);
        row("Break-even days",                breakEvens);
        rowPct("Win rate",                    winRate);
        System.out.println();
        rowPnl("Total realised P&L",          totalPnl);
        rowPnl("Average P&L per trade day",   avgPnl);
        rowPnl("Average winning trade",        avgWin);
        rowPnl("Average losing trade",        -avgLoss);
        rowPct("Profit factor",               profitFactor);
        System.out.println();
        rowPnl("Best single day",             maxProfit.orElse(0));
        rowPnl("Worst single day",            maxLoss.orElse(0));
        rowPct("Sharpe ratio (annualised)",   sharpe);
        System.out.println();
    }

    private void printExitBreakdown() {
        sep("EXIT REASON BREAKDOWN");
        Map<BacktestTrade.ExitReason, Long>   countByReason = new LinkedHashMap<>();
        Map<BacktestTrade.ExitReason, Double> pnlByReason   = new LinkedHashMap<>();

        for (BacktestTrade.ExitReason r : BacktestTrade.ExitReason.values()) {
            long   cnt = activeTrades.stream().filter(t -> t.exitReason == r).count();
            double pnl = activeTrades.stream().filter(t -> t.exitReason == r)
                .mapToDouble(t -> t.realisedPnl).sum();
            if (cnt > 0) {
                countByReason.put(r, cnt);
                pnlByReason.put(r, pnl);
            }
        }

        System.out.printf("  %-22s %8s %12s%n", "Exit Reason", "Count", "Total P&L");
        System.out.println("  " + "─".repeat(44));
        for (BacktestTrade.ExitReason r : countByReason.keySet()) {
            System.out.printf("  %-22s %8d %12s%n",
                r, countByReason.get(r), fmtPnl(pnlByReason.get(r)));
        }
        System.out.println();
    }

    private void printDrawdown() {
        sep("DRAWDOWN ANALYSIS");

        List<Double> cumPnl = new ArrayList<>();
        double running = 0;
        for (BacktestTrade t : activeTrades) {
            running += t.realisedPnl;
            cumPnl.add(running);
        }

        double peak = Double.NEGATIVE_INFINITY;
        double maxDrawdown = 0;
        double drawdownStart = 0;
        double worstPeak = 0;
        double worstTrough = 0;

        for (double val : cumPnl) {
            if (val > peak) {
                peak = val;
                drawdownStart = val;
            }
            double dd = peak - val;
            if (dd > maxDrawdown) {
                maxDrawdown  = dd;
                worstPeak    = peak;
                worstTrough  = val;
            }
        }

        rowPnl("Max drawdown (₹)",            -maxDrawdown);
        rowPnl("  Peak cumulative P&L",        worstPeak);
        rowPnl("  Trough cumulative P&L",      worstTrough);
        rowPnl("Final cumulative P&L",         running);
        System.out.println();
    }

    private void printStreaks() {
        sep("STREAKS");
        int maxWinStreak = 0, maxLossStreak = 0;
        int curWin = 0, curLoss = 0;

        for (BacktestTrade t : activeTrades) {
            if (t.realisedPnl > 0) {
                curWin++;
                curLoss = 0;
                maxWinStreak = Math.max(maxWinStreak, curWin);
            } else if (t.realisedPnl < 0) {
                curLoss++;
                curWin = 0;
                maxLossStreak = Math.max(maxLossStreak, curLoss);
            } else {
                curWin = 0;
                curLoss = 0;
            }
        }

        row("Max consecutive wins",   maxWinStreak);
        row("Max consecutive losses", maxLossStreak);
        System.out.println();
    }

    private void printMonthlyBreakdown() {
        sep("MONTHLY P&L BREAKDOWN");

        Map<String, Double> monthly = new LinkedHashMap<>();
        Map<String, Integer> monthlyCount = new LinkedHashMap<>();

        for (BacktestTrade t : activeTrades) {
            String key = t.date.getYear() + "-" +
                String.format("%02d", t.date.getMonthValue()) + " " +
                t.date.getMonth().name().substring(0, 3);
            monthly.merge(key, t.realisedPnl, Double::sum);
            monthlyCount.merge(key, 1, Integer::sum);
        }

        System.out.printf("  %-12s %8s %10s %12s%n", "Month", "Trades", "Avg/Day", "Total P&L");
        System.out.println("  " + "─".repeat(46));
        double cumulative = 0;
        for (String m : monthly.keySet()) {
            double pnl   = monthly.get(m);
            int    cnt   = monthlyCount.get(m);
            cumulative  += pnl;
            System.out.printf("  %-12s %8d %10s %12s%n",
                m, cnt, fmtPnl(pnl / cnt), fmtPnl(pnl));
        }
        System.out.println("  " + "─".repeat(46));
        System.out.printf("  %-12s %8s %10s %12s%n",
            "TOTAL", activeTrades.size(), "", fmtPnl(cumulative));
        System.out.println();
    }

    private void printTradeTable() {
        sep("PER-TRADE DETAIL");
        System.out.printf("  %-12s %6s %8s %8s %8s %8s %8s %10s %18s %12s%n",
            "Date", "VIX", "Spot", "CE", "PE", "CEEntry", "PEEntry", "Exit", "ExitReason", "P&L");
        System.out.println("  " + "─".repeat(108));

        double cumPnl = 0;
        for (BacktestTrade t : trades) {
            if (!t.tradeEntered) {
                System.out.printf("  %-12s %6.1f %8s %8s %8s %8s %8s %10s %18s %12s%n",
                    t.date, t.vixAtEntry, fmt(t.spotAtEntry),
                    "—", "—", "—", "—", "—",
                    t.exitReason, "NO TRADE");
                continue;
            }
            cumPnl += t.realisedPnl;
            System.out.printf("  %-12s %6.1f %8s %8s %8s %8s %8s %10s %18s %12s%n",
                t.date, t.vixAtEntry, fmt(t.spotAtEntry),
                fmt(t.callStrike), fmt(t.putStrike),
                fmt(t.callEntryPrice), fmt(t.putEntryPrice),
                t.exitTime != null ? t.exitTime.toLocalTime().toString() : "—",
                t.exitReason,
                fmtPnl(t.realisedPnl));
        }
        System.out.println("  " + "─".repeat(108));
        System.out.printf("  %-95s %12s%n", "CUMULATIVE P&L", fmtPnl(cumPnl));
        System.out.println();
    }

    private void printFooter() {
        System.out.println("════════════════════════════════════════════════════════════════════");
        System.out.println("  NOTE: Backtest uses Black-Scholes pricing for intraday P&L.");
        System.out.println("  Actual results may differ due to slippage, liquidity, IV skew.");
        System.out.println("  Results are pre-brokerage. Deduct ~₹40/lot/trade for Zerodha.");
        System.out.println("════════════════════════════════════════════════════════════════════");
        System.out.println();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Statistics
    // ════════════════════════════════════════════════════════════════════════

    private double computeSharpe() {
        if (activeTrades.size() < 2) return 0;
        double[] returns = activeTrades.stream()
            .mapToDouble(t -> t.realisedPnl).toArray();
        double mean = Arrays.stream(returns).average().orElse(0);
        double variance = Arrays.stream(returns)
            .map(r -> (r - mean) * (r - mean))
            .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        if (stdDev == 0) return 0;
        // Annualise: multiply by sqrt(252)
        return (mean / stdDev) * Math.sqrt(252);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Formatting helpers
    // ════════════════════════════════════════════════════════════════════════

    private void sep(String title) {
        System.out.println("── " + title + " " + "─".repeat(Math.max(0, 60 - title.length())));
    }

    private void row(String label, int val) {
        System.out.printf("  %-40s %d%n", label, val);
    }

    private void rowPnl(String label, double val) {
        System.out.printf("  %-40s %s%n", label, fmtPnl(val));
    }

    private void rowPct(String label, double val) {
        System.out.printf("  %-40s %.2f%n", label, val);
    }

    private String fmtPnl(double val) {
        String sign = val >= 0 ? "+" : "";
        return sign + String.format("₹%,.2f", val);
    }

    private String fmt(double val) {
        return String.format("%.2f", val);
    }
}
