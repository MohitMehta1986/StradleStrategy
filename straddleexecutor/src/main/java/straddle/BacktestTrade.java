package straddle;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BacktestTrade
 *
 * Immutable record of one simulated trade during backtesting.
 * One trade = one day (entry at open, exit at SL/TP/EOD).
 */
public class BacktestTrade {

    public enum ExitReason { STOP_LOSS, TARGET_PROFIT, EOD_FORCE_EXIT, NO_TRADE_VIX, NO_ENTRY_FOUND, LTP_DIFFERENCE_GREATER_THAN_4 }

    // ── Day context ───────────────────────────────────────────────────────────
    public final LocalDate   date;
    public final double      vixAtEntry;
    public final double      spotAtEntry;
    public final double      atmStrike;

    // ── Trade entry ───────────────────────────────────────────────────────────
    public final boolean     tradeEntered;
    public final double      callStrike;
    public final double      putStrike;
    public final double      callEntryPrice;   // premium received (sell price)
    public final double      putEntryPrice;
    public final double      callDelta;
    public final double      putDelta;
    public final double      totalCreditReceived;   // (call + put) × lotSize × lots

    // ── Trade exit ────────────────────────────────────────────────────────────
    public final double      callExitPrice;
    public final double      putExitPrice;
    public final LocalDateTime exitTime;
    public final ExitReason  exitReason;

    // ── P&L ───────────────────────────────────────────────────────────────────
    public final double      realisedPnl;      // positive = profit, negative = loss
    public final int         quantity;          // per leg (lots × lotSize)

    private BacktestTrade(Builder b) {
        this.date                = b.date;
        this.vixAtEntry          = b.vixAtEntry;
        this.spotAtEntry         = b.spotAtEntry;
        this.atmStrike           = b.atmStrike;
        this.tradeEntered        = b.tradeEntered;
        this.callStrike          = b.callStrike;
        this.putStrike           = b.putStrike;
        this.callEntryPrice      = b.callEntryPrice;
        this.putEntryPrice       = b.putEntryPrice;
        this.callDelta           = b.callDelta;
        this.putDelta            = b.putDelta;
        this.totalCreditReceived = b.totalCreditReceived;
        this.callExitPrice       = b.callExitPrice;
        this.putExitPrice        = b.putExitPrice;
        this.exitTime            = b.exitTime;
        this.exitReason          = b.exitReason;
        this.realisedPnl         = b.realisedPnl;
        this.quantity            = b.quantity;
    }

    /** Compact one-line summary for logging */
    @Override
    public String toString() {
        if (!tradeEntered) {
            return String.format("[%s] NO TRADE | VIX=%.2f | Reason=%s", date, vixAtEntry, exitReason);
        }
        return String.format(
            "[%s] CE=%.0f@₹%.2f PUT=%.0f@₹%.2f | Exit=%s@%s | PnL=₹%.2f",
            date, callStrike, callEntryPrice, putStrike, putEntryPrice,
            exitReason, exitTime != null ? exitTime.toLocalTime() : "—", realisedPnl
        );
    }

    // ── Factory for no-trade days ─────────────────────────────────────────────
    public static BacktestTrade noTrade(LocalDate date, double vix,
                                         double spot, double atm, ExitReason reason) {
        return new Builder()
            .date(date).vixAtEntry(vix).spotAtEntry(spot).atmStrike(atm)
            .tradeEntered(false).exitReason(reason)
            .build();
    }

    // ── Builder ───────────────────────────────────────────────────────────────
    public static class Builder {
        LocalDate     date;
        double        vixAtEntry, spotAtEntry, atmStrike;
        boolean       tradeEntered;
        double        callStrike, putStrike;
        double        callEntryPrice, putEntryPrice;
        double        callDelta, putDelta;
        double        totalCreditReceived;
        double        callExitPrice, putExitPrice;
        LocalDateTime exitTime;
        ExitReason    exitReason = ExitReason.EOD_FORCE_EXIT;
        double        realisedPnl;
        int           quantity;

        public Builder date(LocalDate v)              { this.date = v; return this; }
        public Builder vixAtEntry(double v)           { this.vixAtEntry = v; return this; }
        public Builder spotAtEntry(double v)          { this.spotAtEntry = v; return this; }
        public Builder atmStrike(double v)            { this.atmStrike = v; return this; }
        public Builder tradeEntered(boolean v)        { this.tradeEntered = v; return this; }
        public Builder callStrike(double v)           { this.callStrike = v; return this; }
        public Builder putStrike(double v)            { this.putStrike = v; return this; }
        public Builder callEntryPrice(double v)       { this.callEntryPrice = v; return this; }
        public Builder putEntryPrice(double v)        { this.putEntryPrice = v; return this; }
        public Builder callDelta(double v)            { this.callDelta = v; return this; }
        public Builder putDelta(double v)             { this.putDelta = v; return this; }
        public Builder totalCreditReceived(double v)  { this.totalCreditReceived = v; return this; }
        public Builder callExitPrice(double v)        { this.callExitPrice = v; return this; }
        public Builder putExitPrice(double v)         { this.putExitPrice = v; return this; }
        public Builder exitTime(LocalDateTime v)      { this.exitTime = v; return this; }
        public Builder exitReason(ExitReason v)       { this.exitReason = v; return this; }
        public Builder realisedPnl(double v)          { this.realisedPnl = v; return this; }
        public Builder quantity(int v)                { this.quantity = v; return this; }

        public BacktestTrade build()                  { return new BacktestTrade(this); }
    }
}
