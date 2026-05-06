package straddle;


import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * BacktestEngine
 *
 * Simulates the NIFTY short strangle strategy on historical data.
 *
 * ── Per-day simulation ────────────────────────────────────────────────────
 *
 *   For each trading day in the dataset:
 *
 *   1. VIX Gate check (same as live):
 *      • VIX ≥ 20 → NO TRADE
 *      • VIX not in [14, 16] → NO TRADE
 *
 *   2. Strike selection (same logic as StrikeSelector):
 *      • OTM Call + Put with premium < ₹100, delta ~0.20–0.23, diff ≤ 0.03
 *
 *   3. Entry at 09:16 open price (first candle after market open)
 *
 *   4. Intraday simulation — walk minute candles:
 *      • Recompute option LTP via Black-Scholes with updated spot + time decay
 *      • Check SL (portfolio loss ≥ ₹650) and TP (portfolio profit ≥ ₹1200)
 *      • Force exit at 15:15 (15 min before close)
 *
 *   5. Record BacktestTrade with entry, exit, P&L, reason
 */
public class BacktestEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime ENTRY_TIME      = LocalTime.of(9, 16);
    private static final LocalTime FORCE_EXIT_TIME = LocalTime.of(15, 15);

    private final TradeConfig        config;
    private final BacktestDataFetcher fetcher;
    private final StrikeSelector     selector;

    public BacktestEngine(KiteConnect kite, TradeConfig config) throws KiteException, IOException {
        this.config   = config;
        this.fetcher  = new BacktestDataFetcher(kite, config);
        this.selector = new StrikeSelector(config);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Run the full backtest over the last 90 trading days.
     * Fetches data from Zerodha, simulates each day, returns all trades.
     */
    public List<BacktestTrade> run() throws KiteException, IOException {
        // Fetch ~130 calendar days to get ~90 trading days
        List<BacktestDataFetcher.BacktestDay> days = fetcher.fetchBacktestDays(130);
        Logger.info("Simulating " + days.size() + " trading days...\n");

        List<BacktestTrade> trades = new ArrayList<>();

        for (BacktestDataFetcher.BacktestDay day : days) {
            BacktestTrade trade = simulateDay(day);
            trades.add(trade);
            Logger.info(trade.toString());
        }

        return trades;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Per-day simulation
    // ════════════════════════════════════════════════════════════════════════

    private BacktestTrade simulateDay(BacktestDataFetcher.BacktestDay day) {

        // ── VIX Gate ──────────────────────────────────────────────────────────
        if (day.vix >= config.vixNoTradeThreshold) {
            return BacktestTrade.noTrade(day.date, day.vix, day.spotAtEntry,
                day.atmStrike, BacktestTrade.ExitReason.NO_TRADE_VIX);
        }

        double vixLow  = config.vixEntryTarget - config.vixEntryBand;
        double vixHigh = config.vixEntryTarget + config.vixEntryBand;
        if (day.vix < vixLow || day.vix > vixHigh) {
            return BacktestTrade.noTrade(day.date, day.vix, day.spotAtEntry,
                day.atmStrike, BacktestTrade.ExitReason.NO_TRADE_VIX);
        }

        // ── Strike Selection ──────────────────────────────────────────────────
        MarketSnapshot snapshot = new MarketSnapshot(
            day.date.atTime(ENTRY_TIME), day.spotAtEntry,
            day.vix, day.atmStrike, day.optionChain);

        Optional<StrikeSelector.SelectedStrikes> selected = selector.select(snapshot);
        if (selected.isEmpty()) {
            return BacktestTrade.noTrade(day.date, day.vix, day.spotAtEntry,
                day.atmStrike, BacktestTrade.ExitReason.NO_ENTRY_FOUND);
        }

        if(Math.abs(selected.get().call.getLtp()-selected.get().put.getLtp()) > Double.parseDouble("4"))
        {
            Logger.info("ltp differences is more than 3. Retrying next poll.");
            return BacktestTrade.noTrade(day.date, day.vix, day.spotAtEntry,
                    day.atmStrike, BacktestTrade.ExitReason.LTP_DIFFERENCE_GREATER_THAN_4);
        }

        StrikeSelector.SelectedStrikes strikes = selected.get();
        OptionContract call = strikes.call;
        OptionContract put  = strikes.put;
        int qty = config.lots * config.lotSize;

        double callEntry = call.getLtp();
        double putEntry  = put.getLtp();
        double totalCredit = (callEntry + putEntry) * qty;

        Logger.debug(String.format("  %s ENTRY | CE=%.0f@₹%.2f PUT=%.0f@₹%.2f | Credit=₹%.2f",
            day.date, call.getStrikePrice(), callEntry,
            put.getStrikePrice(), putEntry, totalCredit));

        // ── Intraday Simulation ───────────────────────────────────────────────
        return simulateIntraday(day, call, put, callEntry, putEntry, qty);
    }

    /**
     * Walks intraday minute candles, recomputes option prices via Black-Scholes,
     * and checks SL/TP at each minute until exit or EOD.
     */
    private BacktestTrade simulateIntraday(BacktestDataFetcher.BacktestDay day,
                                            OptionContract callContract,
                                            OptionContract putContract,
                                            double callEntry, double putEntry,
                                            int qty) {
        double sigma = day.vix / 100.0;
        double r     = 0.065;

        LocalDate expiry    = parseExpiry(callContract.getExpiry());
        double    callStrike = callContract.getStrikePrice();
        double    putStrike  = putContract.getStrikePrice();

        double callExitPrice = callEntry;
        double putExitPrice  = putEntry;
        LocalDateTime exitTime = day.date.atTime(FORCE_EXIT_TIME);
        BacktestTrade.ExitReason exitReason  = BacktestTrade.ExitReason.EOD_FORCE_EXIT;

        // Walk each intraday candle
        for (HistoricalData candle : day.intradayCandles) {
            LocalDateTime ts = ZonedDateTime.parse(candle.timeStamp)
                    .withZoneSameInstant(IST)
                    .toLocalDateTime();
            LocalTime     lt  = ts.toLocalTime();

            if (lt.isBefore(ENTRY_TIME)) continue;  // before entry
            if (!lt.isBefore(FORCE_EXIT_TIME)) {    // at/after force exit time
                callExitPrice = callLtp(candle.close, callStrike, sigma, r, expiry, day.date);
                putExitPrice  = putLtp(candle.close, putStrike,  sigma, r, expiry, day.date);
                exitTime      = ts;
                exitReason    = BacktestTrade.ExitReason.EOD_FORCE_EXIT;
                break;
            }

            // Recompute option LTPs using Black-Scholes with current spot + time
            double cLtp = callLtp(candle.close, callStrike, sigma, r, expiry, day.date);
            double pLtp = putLtp(candle.close, putStrike,  sigma, r, expiry, day.date);

            // P&L for short strangle: profit = premium collected - current premium
            // (entry credit - current mark-to-market cost to close)
            double pnl = ((callEntry - cLtp) + (putEntry - pLtp)) * qty;

            // ── Stop-Loss ─────────────────────────────────────────────────────
            if (pnl <= -config.stopLossAmount) {
                callExitPrice = cLtp;
                putExitPrice  = pLtp;
                exitTime      = ts;
                exitReason    = BacktestTrade.ExitReason.STOP_LOSS;
                Logger.debug(String.format("    SL hit @ %s | PnL=₹%.2f", lt, pnl));
                break;
            }

            // ── Take-Profit ───────────────────────────────────────────────────
            if (pnl >= config.targetProfitAmount) {
                callExitPrice = cLtp;
                putExitPrice  = pLtp;
                exitTime      = ts;
                exitReason    = BacktestTrade.ExitReason.TARGET_PROFIT;
                Logger.debug(String.format("    TP hit @ %s | PnL=₹%.2f", lt, pnl));
                break;
            }
        }

        // Final P&L
        double realisedPnl = ((callEntry - callExitPrice) + (putEntry - putExitPrice)) * qty;

        return new BacktestTrade.Builder()
            .date(day.date)
            .vixAtEntry(day.vix)
            .spotAtEntry(day.spotAtEntry)
            .atmStrike(day.atmStrike)
            .tradeEntered(true)
            .callStrike(callStrike)
            .putStrike(putStrike)
            .callEntryPrice(callEntry)
            .putEntryPrice(putEntry)
            .callDelta(callContract.getDelta())
            .putDelta(putContract.getDelta())
            .totalCreditReceived((callEntry + putEntry) * qty)
            .callExitPrice(callExitPrice)
            .putExitPrice(putExitPrice)
            .exitTime(exitTime)
            .exitReason(exitReason)
            .realisedPnl(realisedPnl)
            .quantity(qty)
            .build();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Black-Scholes option LTP with time decay
    // ════════════════════════════════════════════════════════════════════════

    private double callLtp(double spot, double strike, double sigma,
                            double r, LocalDate expiry, LocalDate today) {
        double T = timeToExpiry(today, expiry);
        return Math.max(0.05, BlackScholes.compute(spot, strike, r, sigma, T, true).price);
    }

    private double putLtp(double spot, double strike, double sigma,
                           double r, LocalDate expiry, LocalDate today) {
        double T = timeToExpiry(today, expiry);
        return Math.max(0.05, BlackScholes.compute(spot, strike, r, sigma, T, false).price);
    }

    private double timeToExpiry(LocalDate from, LocalDate to) {
        double days = Math.max(to.toEpochDay() - from.toEpochDay(), 0.001);
        return days / 365.0;
    }

    private LocalDate parseExpiry(String expiry) {
        try {
            return LocalDate.parse(expiry, DateTimeFormatter.ofPattern("ddMMMyy"));
        } catch (Exception e) {
            return LocalDate.now().plusDays(7);
        }
    }
}
