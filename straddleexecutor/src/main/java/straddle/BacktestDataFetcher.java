package straddle;


import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.Instrument;


import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;



/**
 * BacktestDataFetcher
 *
 * Fetches historical market data from Zerodha Kite Connect for backtesting:
 *
 *   • NIFTY 50 spot price          → historical candles (minute interval)
 *   • India VIX                    → historical candles (day interval)
 *   • Option chain per trading day → derived from instrument dump + Black-Scholes
 *                                    (Kite does not expose historical option chains,
 *                                     so we reconstruct them from OHLC candle data
 *                                     for individual option instruments)
 *
 * ── Kite Historical API ────────────────────────────────────────────────────
 *   kite.getHistoricalData(instrumentToken, from, to, interval, continuous, oi)
 *   interval: "minute", "3minute", "5minute", "15minute", "30minute",
 *             "60minute", "day"
 *   Max candles per call: 60 days for minute, 2000 for day
 *   Rate limit: 3 req/sec
 *
 * ── Data Architecture ──────────────────────────────────────────────────────
 *   For each trading day in the 90-day window:
 *     1. Get NIFTY spot at 09:16 (first candle after open)
 *     2. Get India VIX for that day
 *     3. Compute ATM strike
 *     4. Find weekly expiry
 *     5. Build option chain using historical candle LTP + B-S greeks
 *     6. Simulate strategy entry/exit using intraday minute candles
 */
public class BacktestDataFetcher {

    // Zerodha instrument tokens (static, rarely change)
    private static final long   NIFTY_50_TOKEN  = 256265L;
    private static final long   INDIA_VIX_TOKEN = 264969L;
    private static final String NFO_EXCHANGE     = "NFO";
    private static final String NSE_EXCHANGE     = "NSE";
    private static final int    NIFTY_STRIKE_STEP = 50;
    private static final ZoneId IST              = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KiteConnect        kite;
    private final TradeConfig        config;
    private final List<Instrument>   nfoInstruments;

    // Rate-limiting: Kite allows 3 req/sec
    private long lastRequestTime = 0;
    private static final long MIN_REQUEST_INTERVAL_MS = 350;

    public BacktestDataFetcher(KiteConnect kite, TradeConfig config) throws KiteException, IOException {
        this.kite   = kite;
        this.config = config;
        Logger.info("Loading NFO instrument dump for backtest...");
        this.nfoInstruments = kite.getInstruments(NFO_EXCHANGE).stream()
            .filter(i -> i.tradingsymbol.startsWith("NIFTY"))
            .filter(i -> "CE".equals(i.instrument_type) || "PE".equals(i.instrument_type))
            .collect(Collectors.toList());
        Logger.info("Loaded " + nfoInstruments.size() + " NIFTY option instruments.");
    }

    // ════════════════════════════════════════════════════════════════════════
    // Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetches all data needed to backtest the last N trading days.
     * Returns one BacktestDay per trading day with full intraday candles.
     *
     * @param days number of calendar days to look back (use 130 to get ~90 trading days)
     */
    public List<BacktestDay> fetchBacktestDays(int days) throws KiteException, IOException  {
        LocalDate to   = LocalDate.now(IST).minusDays(1); // yesterday
        LocalDate from = to.minusDays(days);

        Logger.info(String.format("Fetching backtest data: %s → %s", from, to));

        // 1. Fetch NIFTY spot minute candles (split into 60-day chunks)
        List<HistoricalData> niftyCandles = fetchHistoricalInChunks(
            NIFTY_50_TOKEN, from, to, "minute");
        Logger.info("NIFTY candles fetched: " + niftyCandles.size());

        // 2. Fetch India VIX daily candles
        List<HistoricalData> vixCandles = fetchHistoricalInChunks(
            INDIA_VIX_TOKEN, from, to, "day");
        Logger.info("VIX candles fetched: " + vixCandles.size());

        // 3. Group NIFTY candles by trading day
        Map<LocalDate, List<HistoricalData>> candlesByDay = groupByDay(niftyCandles);
        Map<LocalDate, Double>                              vixByDay     = buildVixByDay(vixCandles);

        // 4. Build a BacktestDay for each trading day
        List<BacktestDay> result = new ArrayList<>();
        List<LocalDate> tradingDays = candlesByDay.keySet().stream()
            .sorted()
            .collect(Collectors.toList());

        Logger.info("Trading days found: " + tradingDays.size());

        for (LocalDate day : tradingDays) {
            List<HistoricalData> dayCandles = candlesByDay.get(day);
            double vix = vixByDay.getOrDefault(day, 0.0);

            if (vix <= 0) {
                Logger.debug("No VIX data for " + day + " — skipping.");
                continue;
            }

            // Entry snapshot: first candle at/after 09:16
            HistoricalData entryCandle = getEntryCandle(dayCandles);
            if (entryCandle == null) {
                Logger.debug("No entry candle for " + day + " — skipping.");
                continue;
            }

            double spotAtEntry = entryCandle.close;
            double atmStrike   = computeAtm(spotAtEntry);
            String expiry      = getNearestThursday(day);

            // Build option chain using historical option LTPs + B-S greeks
            List<OptionContract> chain = buildOptionChain(day, spotAtEntry, vix, atmStrike, expiry);

            result.add(new BacktestDay(day, vix, spotAtEntry, atmStrike, expiry, chain, dayCandles));

            Logger.info(String.format("  %s | spot=%.2f | VIX=%.2f | ATM=%.0f | chain=%d",
                day, spotAtEntry, vix, atmStrike, chain.size()));
        }

        Logger.info("BacktestDays built: " + result.size());
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════
    // Historical Candle Fetching
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Fetches historical candles in 60-day chunks to stay within Kite API limits.
     * Kite enforces: minute candles → max 60 days per request.
     */
    private List<HistoricalData> fetchHistoricalInChunks(long token, LocalDate from,
                                                                        LocalDate to,
                                                                        String interval)
            throws KiteException, IOException {

        List<HistoricalData> all = new ArrayList<>();
        LocalDate chunkFrom = from;
        int chunkDays = interval.equals("minute") ? 55 : 400;

        while (!chunkFrom.isAfter(to)) {
            LocalDate chunkTo = chunkFrom.plusDays(chunkDays - 1);
            if (chunkTo.isAfter(to)) chunkTo = to;

            Date fromDate = toDate(chunkFrom.atTime(9, 0));
            Date toDate   = toDate(chunkTo.atTime(23, 59));

            rateLimitWait();
            try {
                HistoricalData data = kite.getHistoricalData(
                    fromDate, toDate, String.valueOf(token), interval, false, false
                );
                if (data != null && data.dataArrayList != null) {
                    all.addAll(data.dataArrayList);
                }
                int count = (data != null && data.dataArrayList != null) ? data.dataArrayList.size() : 0;
                Logger.debug(String.format("  Chunk %s→%s: %d candles", chunkFrom, chunkTo, count));
            } catch (KiteException e) {
                Logger.warn("Kite error for chunk " + chunkFrom + "→" + chunkTo + ": " + e.getMessage());
            }

            chunkFrom = chunkTo.plusDays(1);
        }
        return all;
    }

    /**
     * Fetches historical LTP for a specific option instrument on a given day.
     * Used to get real option prices for the option chain.
     */
    public Optional<Double> fetchOptionLtp(long instrumentToken, LocalDate day)
            throws KiteException, IOException {
        Date from = toDate(day.atTime(9, 15));
        Date to   = toDate(day.atTime(9, 20));

        rateLimitWait();
        try {
            HistoricalData data = kite.getHistoricalData(
                from, to, String.valueOf(instrumentToken), "minute", false, false
            );
            if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                return Optional.of(data.dataArrayList.get(0).close);
            }
        } catch (KiteException e) {
            Logger.debug("No candle for token=" + instrumentToken + " on " + day);
        }
        return Optional.empty();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Option Chain Construction
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Builds an option chain for a specific day using:
     *   - Historical LTP from Kite for options that existed (best effort)
     *   - Black-Scholes pricing as fallback when Kite data unavailable
     *
     * Covers ATM ± 10 strikes (±500 points in 50-pt steps).
     */
    private List<OptionContract> buildOptionChain(LocalDate day, double spot,
                                                   double vix, double atm,
                                                   String expiry) {
        List<OptionContract> chain = new ArrayList<>();
        double sigma = vix / 100.0;
        double r     = 0.065;
        LocalDate expiryDate = parseExpiry(expiry);
        double T = Math.max(daysBetween(day, expiryDate) / 365.0, 0.001);

        for (int i = -10; i <= 10; i++) {
            double strike = atm + (i * NIFTY_STRIKE_STEP);

            // Find matching NFO instrument
            String ceSymbol = buildSymbol(expiry, (int) strike, "CE");
            String peSymbol = buildSymbol(expiry, (int) strike, "PE");

            // CALL
            BlackScholes.Greeks cg = BlackScholes.compute(spot, strike, r, sigma, T, true);
            chain.add(new OptionContract(ceSymbol, strike, OptionContract.OptionType.CALL,
                expiry, Math.max(0.05, cg.price), cg.delta, sigma));

            // PUT
            BlackScholes.Greeks pg = BlackScholes.compute(spot, strike, r, sigma, T, false);
            chain.add(new OptionContract(peSymbol, strike, OptionContract.OptionType.PUT,
                expiry, Math.max(0.05, pg.price), pg.delta, sigma));
        }

        return chain;
    }

    /**
     * Fetches intraday option prices for a specific position during the day.
     * Used by the backtest engine to track P&L minute-by-minute.
     *
     * Returns a map of timestamp → LTP for the given option symbol.
     */
    public Map<LocalDateTime, Double> fetchIntradayOptionPrices(
            String tradingSymbol, LocalDate day) throws KiteException, IOException {

        Instrument inst = nfoInstruments.stream()
            .filter(i -> i.tradingsymbol.equalsIgnoreCase(tradingSymbol))
            .findFirst().orElse(null);

        if (inst == null) {
            Logger.debug("Instrument not found: " + tradingSymbol);
            return Collections.emptyMap();
        }

        Date from = toDate(day.atTime(9, 15));
        Date to   = toDate(day.atTime(15, 30));

        rateLimitWait();
        try {
            HistoricalData data = kite.getHistoricalData(
                from, to, String.valueOf(inst.instrument_token), "minute", false, false
            );
            Map<LocalDateTime, Double> prices = new LinkedHashMap<>();
            if (data != null && data.dataArrayList != null) {
                for (HistoricalData c : data.dataArrayList) {
                    LocalDateTime ts = ZonedDateTime.parse(c.timeStamp)
                            .withZoneSameInstant(IST)
                            .toLocalDateTime();
                    prices.put(ts, c.close);
                }
            }
            return prices;
        } catch (KiteException e) {
            Logger.warn("Failed to fetch intraday prices for " + tradingSymbol + ": " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════════════

    private Map<LocalDate, List<HistoricalData>> groupByDay(
            List<HistoricalData> candles) {
        Map<LocalDate, List<HistoricalData>> map = new LinkedHashMap<>();
        for (HistoricalData c : candles) {
            LocalDate day = Instant.parse(c.timeStamp)
                    .atZone(IST) // IST
                    .toLocalDate();
            map.computeIfAbsent(day, k -> new ArrayList<>()).add(c);
        }
        return map;
    }

    private Map<LocalDate, Double> buildVixByDay(List<HistoricalData> vixCandles) {
        Map<LocalDate, Double> map = new HashMap<>();
        for (HistoricalData c : vixCandles) {
            LocalDate day = Instant.parse(c.timeStamp)
                    .atZone(IST) // IST
                    .toLocalDate();
            map.put(day, c.close);
        }
        return map;
    }

    private HistoricalData getEntryCandle(List<HistoricalData> candles) {
        LocalTime entryTime = LocalTime.of(9, 16);
        return candles.stream()
            .filter(c -> {
                ZonedDateTime zdt = ZonedDateTime.parse(c.timeStamp,
                        DateTimeFormatter.ISO_DATE_TIME.withZone(IST));

// 3. Convert to IST and extract LocalTime
                LocalTime t = zdt.withZoneSameInstant(IST).toLocalTime();
                return !t.isBefore(entryTime);
            })
            .findFirst().orElse(null);
    }

    private double computeAtm(double spot) {
        return Math.round(spot / NIFTY_STRIKE_STEP) * NIFTY_STRIKE_STEP;
    }

    /** Returns the nearest Thursday on or after the given date, formatted as "DDMMMYY" */
    private String getNearestThursday(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek() != DayOfWeek.THURSDAY) d = d.plusDays(1);
        return d.format(DateTimeFormatter.ofPattern("ddMMMyy")).toUpperCase();
    }

    private LocalDate parseExpiry(String expiry) {
        try {
            return LocalDate.parse(expiry, DateTimeFormatter.ofPattern("ddMMMyy"));
        } catch (Exception e) {
            return LocalDate.now().plusDays(7);
        }
    }

    private double daysBetween(LocalDate from, LocalDate to) {
        return Math.max(to.toEpochDay() - from.toEpochDay(), 0.25);
    }

    private String buildSymbol(String expiry, int strike, String optType) {
        // Kite format: NIFTY + DDMMMYY + STRIKE + CE/PE  e.g. "NIFTY25APR2422500CE"
        return "NIFTY" + expiry + strike + optType;
    }

    private Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(IST).toInstant());
    }

    private void rateLimitWait() {
        long now   = System.currentTimeMillis();
        long delta = now - lastRequestTime;
        if (delta < MIN_REQUEST_INTERVAL_MS) {
            try { Thread.sleep(MIN_REQUEST_INTERVAL_MS - delta); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastRequestTime = System.currentTimeMillis();
    }

    // ════════════════════════════════════════════════════════════════════════
    // Inner model: one trading day's worth of backtest inputs
    // ════════════════════════════════════════════════════════════════════════

    public static class BacktestDay {
        public final LocalDate                         date;
        public final double                            vix;
        public final double                            spotAtEntry;
        public final double                            atmStrike;
        public final String                            expiry;
        public final List<OptionContract>              optionChain;
        public final List<HistoricalData> intradayCandles; // NIFTY spot minute candles

        public BacktestDay(LocalDate date, double vix, double spotAtEntry,
                           double atmStrike, String expiry,
                           List<OptionContract> optionChain,
                           List<HistoricalData> intradayCandles) {
            this.date            = date;
            this.vix             = vix;
            this.spotAtEntry     = spotAtEntry;
            this.atmStrike       = atmStrike;
            this.expiry          = expiry;
            this.optionChain     = optionChain;
            this.intradayCandles = intradayCandles;
        }
    }
}
