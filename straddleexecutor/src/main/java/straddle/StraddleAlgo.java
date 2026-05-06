package straddle;


import com.zerodhatech.kiteconnect.KiteConnect;

/**
 * NIFTY50 Straddle Algorithm — Zerodha Edition
 *
 * ── Pre-flight checklist ──────────────────────────────────────────────────
 *
 *  1. Set environment variables:
 *       export KITE_API_KEY=your_api_key
 *       export KITE_API_SECRET=your_api_secret
 *       export KITE_REQUEST_TOKEN=token_from_today_login
 *
 *  2. Get today's request_token (do before 09:15 each day):
 *       java -jar straddle-algo.jar --login
 *       → Prints login URL. Complete login. Copy request_token from redirect URL.
 *
 *  3. Run:
 *       java -jar straddle-algo-jar-with-dependencies.jar
 */
public class StraddleAlgo {

    public static void main(String[] args) throws Exception {

//        // --login flag: just print the Kite OAuth URL and exit
//        if (args.length > 0 && args[0].equals("--login")) {
//            KiteSessionManager.printLoginUrl();
//            return;
//        }
//
//        Logger.info("═══════════════════════════════════════════════════════════");
//        Logger.info("   NIFTY50 Short Strangle Algorithm — Zerodha Edition");
//        Logger.info("═══════════════════════════════════════════════════════════");
//
//        // Authenticate with Zerodha Kite Connect
//        KiteSessionManager session = new KiteSessionManager();
//        KiteConnect kite = session.authenticate();
//
//        TradeConfig config   = TradeConfig.defaultConfig();
//        Logger.info("Config: " + config);
//
//        PortfolioMonitor  monitor = new PortfolioMonitor(config);
//        ZerodhaTradeEngine engine = new ZerodhaTradeEngine(config, kite, monitor);
//
//        // Load NFO instrument dump (needed for option chain resolution)
//        engine.initialize();
//
//        // Graceful shutdown on Ctrl+C
//        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
//            Logger.warn("Shutdown signal — stopping engine and squaring off if needed.");
//            engine.stop();
//        }));
//
//        engine.run();
//    }
    }
}
