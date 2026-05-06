package straddle;


import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Optional;

/**
 * ZerodhaTradeEngine
 *
 * Full Zerodha-integrated trade engine. Replaces the simulated TradeEngine
 * with live market data from KiteConnect and real order placement.
 *
 * State machine: SCANNING → IN_TRADE → EXITED → STOPPED
 */
public class ZerodhaTradeEngine {

    private enum State { IDLE, SCANNING, IN_TRADE, EXITED, STOPPED }

    private final TradeConfig              config;
    private final KiteConnect              kite;
    private final PortfolioMonitor         monitor;
    private final ZerodhaMarketDataService marketData;
    private final ZerodhaOrderExecutor     executor;
    private final StrikeSelector           selector;
    private final SidewaysMarketDetector   sidewaysDetector;

    private State            state    = State.IDLE;
    private StraddlePosition position = null;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    public ZerodhaTradeEngine(TradeConfig config, KiteConnect kite, PortfolioMonitor monitor) {
        this.config     = config;
        this.kite       = kite;
        this.monitor    = monitor;
        this.marketData = new ZerodhaMarketDataService(config, kite);
        this.executor   = new ZerodhaOrderExecutor(config);
        this.selector   = new StrikeSelector(config);
        this.sidewaysDetector = new SidewaysMarketDetector(kite);
    }

    /**
     * Must be called once before run() to load instrument cache.
     */
    public void initialize() throws KiteException, IOException {
        marketData.initialize();
        try {
            sidewaysDetector.initialize();
        } catch (Exception e) {
            Logger.warn("Sideways detector seed failed: " + e.getMessage() + " — will build from ticks.");
        }

        state = State.SCANNING;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Main Loop
    // ════════════════════════════════════════════════════════════════════════

    public void run() {
        Logger.info("Engine running. State: " + state);

        while (state != State.STOPPED) {
            try {
                tick();
                Thread.sleep(config.pollingIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.warn("Interrupted. Stopping.");
                state = State.STOPPED;
            } catch (KiteException e) {
                Logger.error("Kite API error: [" + e.code + "] " + e.getMessage());
                handleKiteError(e);
            } catch (IOException e) {
                Logger.error("Network error: " + e.getMessage());
                // Retry on next tick
            } catch (Exception e) {
                Logger.error("Unexpected error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        printSessionSummary();
    }

    public void stop() {
        Logger.info("Stop requested.");
        // If in trade, attempt graceful exit
        if (state == State.IN_TRADE && position != null && !position.isExited()) {
            Logger.warn("Force-exiting open position on shutdown...");
            try {
                exitTrade(StraddlePosition.ExitReason.FORCE_EXIT);
            } catch (Exception | KiteException e) {
                Logger.error("Failed to exit on shutdown: " + e.getMessage());
            }
        }
        marketData.stopTicker();
        state = State.STOPPED;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Tick Logic
    // ════════════════════════════════════════════════════════════════════════

    private void tick() throws KiteException, IOException {
        LocalTime now = LocalTime.now();

        if (!isMarketOpen(now)) {
            if (state != State.IDLE) {
                Logger.info("Outside market hours. Waiting...");
                state = State.IDLE;
            }
            return;
        }

        switch (state) {
            case IDLE:
                state = State.SCANNING;
                break;

            case SCANNING:
                handleScanning();
                break;

            case IN_TRADE:
                handleInTrade(now);
                break;

            case EXITED:
                Logger.info("Position exited. Session complete.");
                state = State.STOPPED;
                break;

            default:
                break;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // SCANNING
    // ════════════════════════════════════════════════════════════════════════

    private void handleScanning() throws KiteException, IOException {
        MarketSnapshot snapshot = marketData.fetchSnapshot();

        // VIX hard block
        if (snapshot.getIndiaVix() >= config.vixNoTradeThreshold) {
            Logger.warn(String.format("🚫 VIX=%.2f ≥ %.0f — NO TRADE.",
                snapshot.getIndiaVix(), config.vixNoTradeThreshold));
            return;
        }

        // VIX entry band
        //  14 < Vix < 19
        double vixLow  = config.vixEntryTarget - config.vixEntryBand;
       // double vixHigh = config.vixEntryTarget + config.vixEntryBand;
        double vixHigh = config.vixNoTradeThreshold - config.vixEntryBand;
        if (snapshot.getIndiaVix() < vixLow || snapshot.getIndiaVix() > vixHigh) {
            Logger.info(String.format("⏳ VIX=%.2f not in entry band [%.1f, %.1f]. Waiting...",
                snapshot.getIndiaVix(), vixLow, vixHigh));
            return;
        }

        Logger.info(String.format("✅ VIX=%.2f in entry window. Scanning strikes...",
            snapshot.getIndiaVix()));

        // ── Sideways market check (every tick, using latest 5-min candles) ────
        // Feed current NIFTY spot as a tick to keep the candle window fresh.
        sidewaysDetector.addTick(snapshot.getNiftySpotPrice(), java.time.LocalDateTime.now());
        SidewaysMarketDetector.SidewaysResult sideways = sidewaysDetector.analyse();

        if (sideways.isTrending()) {
            Logger.warn(String.format(
                    "📈 Market TRENDING (ADX=%.1f, Score=%d) — skipping entry. Waiting for sideways.",
                    sideways.adx, sideways.score));
            return;
        }

        if (sideways.isBorderline()) {
            Logger.info(String.format(
                    "⚠️  Market BORDERLINE (ADX=%.1f, Score=%d) — waiting for cleaner sideways.",
                    sideways.adx, sideways.score));
            return;
        }

        Optional<StrikeSelector.SelectedStrikes> result = selector.select(snapshot);
        if (result.isEmpty()) {
            Logger.info("No suitable strikes this cycle. Retrying next poll.");
            return;
        }

        if(Math.abs(result.get().call.getLtp()-result.get().put.getLtp()) > Double.parseDouble("4"))
        {
            Logger.info("ltp differences is more than 3. Retrying next poll.");
            return;
        }

        enterTrade(result.get());
    }

    // ════════════════════════════════════════════════════════════════════════
    // IN_TRADE
    // ════════════════════════════════════════════════════════════════════════

    private void handleInTrade(LocalTime now) throws KiteException, IOException {
        // Fetch live LTPs (WebSocket cache or REST fallback)
        double callLtp = marketData.fetchLtp(position.getCallLeg().getContract());
        double putLtp  = marketData.fetchLtp(position.getPutLeg().getContract());
        position.updateLtps(callLtp, putLtp);

        // Force-exit check (market closing soon)
        StraddlePosition.ExitReason forceExit = monitor.evaluateForceExit(position, isMarketClosingSoon(now));
        if (forceExit != StraddlePosition.ExitReason.NONE) {
            exitTrade(forceExit);
            return;
        }

        // P&L-based exit
        StraddlePosition.ExitReason reason = monitor.evaluate(position);
        if (reason != StraddlePosition.ExitReason.NONE) {
            exitTrade(reason);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Entry / Exit
    // ════════════════════════════════════════════════════════════════════════

    private void enterTrade(StrikeSelector.SelectedStrikes strikes)
            throws KiteException, IOException {
        Logger.info("▶ Placing entry orders...");

        TradeLeg callLeg = executor.sellCall(strikes.call);
        TradeLeg putLeg  = executor.sellPut(strikes.put);

        position = new StraddlePosition(callLeg, putLeg);
        state    = State.IN_TRADE;

        // Start WebSocket streaming for these contracts
        marketData.startTickerFor(Arrays.asList(
            callLeg.getContract(), putLeg.getContract()
        ));

        Logger.info(String.format(
            "✅ Position live | CALL=%.0f@₹%.2f | PUT=%.0f@₹%.2f | Credit=₹%.2f",
            strikes.call.getStrikePrice(), callLeg.getEntryPrice(),
            strikes.put.getStrikePrice(),  putLeg.getEntryPrice(),
            position.getTotalPremiumCollected()
        ));
    }

    private void exitTrade(StraddlePosition.ExitReason reason) throws KiteException, IOException {
        Logger.info("◀ Exiting position. Reason: " + reason);

        marketData.stopTicker();

        double callExit = executor.buyBack(position.getCallLeg());
        double putExit  = executor.buyBack(position.getPutLeg());

        position.markExited(callExit, putExit, reason, java.time.LocalDateTime.now());
        state = State.EXITED;

        Logger.info(String.format("📋 EXIT DONE | Reason=%s | PnL=₹%.2f",
            reason, position.getTotalRealisedPnl()));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private boolean isMarketOpen(LocalTime now) {
        LocalTime open  = LocalTime.parse(config.marketOpenTime, TIME_FMT);
        LocalTime close = LocalTime.parse(config.marketCloseTime, TIME_FMT);
        return now.isAfter(open) && now.isBefore(close);
    }

    private boolean isMarketClosingSoon(LocalTime now) {
        LocalTime close = LocalTime.parse(config.marketCloseTime, TIME_FMT);
        return now.isAfter(close.minusMinutes(config.forceExitMinutesBeforeClose));
    }

    /**
     * Handle Kite-specific errors:
     *   403 → session expired, stop engine
     *   429 → rate limit, back off
     *   5xx → transient, retry
     */
    private void handleKiteError(KiteException e) {
        if (e.code == 403) {
            Logger.error("Session expired (403). Please renew access_token. Stopping.");
            state = State.STOPPED;
        } else if (e.code == 429) {
            Logger.warn("Rate limit hit (429). Backing off 10 seconds...");
            try { Thread.sleep(10_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        // 5xx → just log and retry on next tick
    }

    private void printSessionSummary() {
        Logger.info("═══════════════════════════════════════════════");
        Logger.info("              SESSION SUMMARY");
        Logger.info("═══════════════════════════════════════════════");
        if (position != null) {
            Logger.info(position.toString());
            Logger.info(String.format("Final PnL  : ₹%.2f",
                position.isExited()
                    ? position.getTotalRealisedPnl()
                    : position.getTotalUnrealisedPnl()));
            Logger.info("Exit Reason: " + position.getExitReason());
        } else {
            Logger.info("No position was entered this session.");
        }
        Logger.info("═══════════════════════════════════════════════");
    }
}
