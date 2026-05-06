package straddle;


import java.time.LocalDateTime;
import java.util.*;

/**
 * MarketDataService
 *
 * In production: replace the simulation methods with actual broker API calls
 * (Zerodha Kite Connect, Angel Broking SmartAPI, Upstox API, etc.).
 *
 * The interface contract is kept stable so swapping implementations is trivial.
 */
public class MarketDataService {

    private final TradeConfig config;

    // Simulated live values — replace with live feed in production
    private double simulatedNiftySpot = 22_350.0;
    private double simulatedVix       = 15.2;

    public MarketDataService(TradeConfig config) {
        this.config = config;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetch a complete market snapshot: spot, VIX, ATM, option chain.
     * Replace internals with real API calls.
     */
    public MarketSnapshot fetchSnapshot() {
        double spot     = fetchNiftySpot();
        double vix      = fetchIndiaVix();
        double atmStrike = computeAtmStrike(spot);
        String expiry   = getNearestWeeklyExpiry();

        List<OptionContract> chain = buildOptionChain(spot, vix, atmStrike, expiry);

        return new MarketSnapshot(LocalDateTime.now(), spot, vix, atmStrike, chain);
    }

    /**
     * Fetch live LTP for a single option contract.
     * Replace with real quote API call.
     */
    public double fetchLtp(OptionContract contract) {
        // Simulation: small random walk around current LTP
        double noise = (Math.random() - 0.5) * 2.0;
        return Math.max(0.05, contract.getLtp() + noise);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ════════════════════════════════════════════════════════════════════════

    /** 
     * PRODUCTION: call broker API for NIFTY spot price.
     * e.g. Kite: kite.getLTP("NSE:NIFTY 50")
     */
    private double fetchNiftySpot() {
        // Simulate small drift
        simulatedNiftySpot += (Math.random() - 0.48) * 15;
        return simulatedNiftySpot;
    }

    /**
     * PRODUCTION: call broker API or NSE for India VIX.
     * e.g. Kite: kite.getLTP("NSE:INDIA VIX")
     */
    private double fetchIndiaVix() {
        // Simulate tiny VIX movement
        simulatedVix += (Math.random() - 0.5) * 0.1;
        return Math.max(10.0, simulatedVix);
    }

    /**
     * ATM strike = nearest 50-point multiple to spot.
     * NIFTY strikes are spaced 50 points apart for weekly options.
     */
    private double computeAtmStrike(double spot) {
        return Math.round(spot / 50.0) * 50.0;
    }

    /**
     * Returns nearest weekly expiry label.
     * PRODUCTION: derive from actual expiry calendar or broker API.
     */
    private String getNearestWeeklyExpiry() {
        // Placeholder — returns a fixed label; replace with real date logic
        return "25APR2024";
    }

    /**
     * Build option chain around ATM ± 10 strikes (50-point intervals).
     * Uses Black-Scholes to compute greeks for simulation.
     * PRODUCTION: fetch directly from broker option chain API.
     */
    private List<OptionContract> buildOptionChain(double spot, double vix,
                                                   double atm, String expiry) {
        List<OptionContract> chain = new ArrayList<>();

        double sigma = vix / 100.0;          // IV from VIX (approximation)
        double r     = 0.065;                 // Risk-free rate
        double T     = 7.0 / 365.0;          // ~1 week to expiry

        // Generate strikes from ATM-500 to ATM+500 (in 50-pt steps)
        for (int i = -10; i <= 10; i++) {
            double strike = atm + (i * 50);

            // CALL
            BlackScholes.Greeks callG = BlackScholes.compute(spot, strike, r, sigma, T, true);
            String callSymbol = "NIFTY" + expiry + (int) strike + "CE";
            chain.add(new OptionContract(callSymbol, strike, OptionContract.OptionType.CALL,
                expiry, Math.max(0.5, callG.price), callG.delta, sigma));

            // PUT
            BlackScholes.Greeks putG = BlackScholes.compute(spot, strike, r, sigma, T, false);
            String putSymbol = "NIFTY" + expiry + (int) strike + "PE";
            chain.add(new OptionContract(putSymbol, strike, OptionContract.OptionType.PUT,
                expiry, Math.max(0.5, putG.price), putG.delta, sigma));
        }

        return chain;
    }
}
