import awesome.code.base.properties.IPropertiesProvider;
import awesome.code.base.service.IJob;
import awesome.code.base.service.exception.ServiceException;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Instrument;
import kitesubscribers.KiteConnectProvider;
import org.apache.commons.lang3.StringUtils;
import straddle.*;

public class StraddleExecutorJob implements IJob {

    private KiteConnect kiteConnect;
    private List<String> indexTobSubscribe;
    private ZerodhaTradeEngine engine;
    private boolean backTest;

    @Override
    public void init(IPropertiesProvider propertiesProvider) throws ServiceException {
        System.out.println("in option loader jon init method");
        String projectId = propertiesProvider.getStringProperty("option.trading.project.id", null);
        String subscriptionId = propertiesProvider.getStringProperty("option.trading.subscription.id", "options-data-topic-sub");
        String instrumentString = propertiesProvider.getStringProperty("option.trading.eligible.instruments", null);
        this.indexTobSubscribe = Arrays.stream(StringUtils.split(instrumentString, ",")).collect(Collectors.toList());
        // kiteSessionManager = new KiteSessionManager();
        //KiteConnect kite = session.authenticate();

        backTest = propertiesProvider.getBooleanProperty("option.trading.straddler.run.backtest", false);
        KiteConnectProvider kiteConnectProvider = new KiteConnectProvider(propertiesProvider);
        try {
            this.kiteConnect = kiteConnectProvider.getKiteSDKForUserID();
            TradeConfig config = TradeConfig.defaultConfig();
            Logger.info("Config: " + config);
            PortfolioMonitor monitor = new PortfolioMonitor(config);
            engine = new ZerodhaTradeEngine(config, kiteConnect, monitor);
        } catch (IOException | KiteException exception) {
            System.out.println("Exception while intializing kit connect");
            throw new ServiceException(exception);
        }
    }

    @Override
    public void executeJob() throws ServiceException {
        // --login flag: just print the Kite OAuth URL and exit
//        if (args.length > 0 && args[0].equals("--login")) {
//            KiteSessionManager.printLoginUrl();
//            return;
//        }

        if (backTest) {
            try {
                runBackTest(kiteConnect);
            } catch (Exception e) {
                e.printStackTrace();
            } catch (KiteException e) {
                e.printStackTrace();
            }
        } else {

            Logger.info("═══════════════════════════════════════════════════════════");
            Logger.info("   NIFTY50 Short Strangle Algorithm — Zerodha Edition");
            Logger.info("═══════════════════════════════════════════════════════════");

            // Authenticate with Zerodha Kite Connect


            // Load NFO instrument dump (needed for option chain resolution)
            try {
                engine.initialize();
            } catch (KiteException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Graceful shutdown on Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Logger.warn("Shutdown signal — stopping engine and squaring off if needed.");
                engine.stop();
            }));

            engine.run();
        }

    }

    private List<Instrument> getAllInstruments() {
        List<Instrument> listOfAllNSEInstruments = new ArrayList<>();
        try (BufferedInputStream inputStream = new BufferedInputStream(new URL("https://api.kite.trade/instruments").openStream());
             InputStreamReader input = new InputStreamReader(inputStream);
             BufferedReader reader = new BufferedReader(input)
        ) {
            // create csvReader object and skip first Line
            CSVReader csvReader = new CSVReaderBuilder(reader)
                    .withSkipLines(1)
                    .build();
            List<String[]> allData = csvReader.readAll();

            for (String[] row : allData) {
                Instrument i = new Instrument();
                if (row[0] != null && row[2] != null && row[3] != null && row[9] != null && row[6] != null) {
                    i.setInstrument_token(Long.parseLong(row[0]));
                    i.setTradingsymbol((row[2]));
                    i.setName((row[3]));
                    i.setStrike((row[6]));
                    i.setInstrument_type((row[9]));
                    listOfAllNSEInstruments.add(i);
                }

            }

        } catch (IOException e) {
            // handles IO exceptions
            System.out.println("Exception while getting all instrument");
            System.exit(0);
        }

        return listOfAllNSEInstruments;
    }

    private void runBackTest(KiteConnect kite) throws Exception, KiteException {
        Logger.info("════════════════════════════════════════════════════════════");
        Logger.info("   NIFTY Short Strangle — 90-Day Backtest");
        Logger.info("════════════════════════════════════════════════════════════");




        // ── Configure strategy parameters for backtesting ─────────────────────
        // Change these to test different parameter combinations
        TradeConfig config = new TradeConfig.Builder()
                .vixNoTradeThreshold(20.0)   // Skip if VIX ≥ 20
                .vixEntryTarget(15.0)        // Enter when VIX ≈ 15
                .maxPremium(100.0)           // Both legs must be < ₹100
                .targetDelta(0.225)          // Target delta 0.20–0.23
                .deltaRange(0.015)
                .maxDeltaDifference(0.03)
                .stopLossAmount(650.0)       // Exit on ₹650 portfolio loss
                .targetProfitAmount(1200.0)  // Exit on ₹1200 portfolio profit
                .lots(2)
                .build();

        Logger.info("Backtest config: " + config);

        // ── Run backtest ──────────────────────────────────────────────────────
        BacktestEngine engine = new BacktestEngine(kite, config);
        List<BacktestTrade> trades = engine.run();

        // ── Analyse & print report ────────────────────────────────────────────
        BacktestAnalyser analyser = new BacktestAnalyser(config, trades);
        analyser.printReport();

        // ── Save results to files ─────────────────────────────────────────────
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        saveCsv(trades, "backtest_results_" + timestamp + ".csv");
        Logger.info("Results saved to backtest_results_" + timestamp + ".csv");
    }

    // ════════════════════════════════════════════════════════════════════════
    // CSV Export
    // ════════════════════════════════════════════════════════════════════════

    private static void saveCsv(List<BacktestTrade> trades, String filename) throws IOException {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("Date,VIX,Spot,ATM,TradeEntered,CallStrike,PutStrike,")
                .append("CallEntryPrice,PutEntryPrice,CallDelta,PutDelta,")
                .append("TotalCredit,CallExitPrice,PutExitPrice,ExitTime,")
                .append("ExitReason,Quantity,RealisedPnL,CumulativePnL\n");

        double cumPnl = 0;
        for (BacktestTrade t : trades) {
            cumPnl += t.realisedPnl;
            sb.append(String.format(
                    "%s,%.2f,%.2f,%.0f,%b,%.0f,%.0f,%.2f,%.2f,%.4f,%.4f,%.2f,%.2f,%.2f,%s,%s,%d,%.2f,%.2f%n",
                    t.date, t.vixAtEntry, t.spotAtEntry, t.atmStrike,
                    t.tradeEntered,
                    t.callStrike, t.putStrike,
                    t.callEntryPrice, t.putEntryPrice,
                    t.callDelta, t.putDelta,
                    t.totalCreditReceived,
                    t.callExitPrice, t.putExitPrice,
                    t.exitTime != null ? t.exitTime.toLocalTime() : "",
                    t.exitReason,
                    t.quantity,
                    t.realisedPnl,
                    cumPnl
            ));
        }

        Files.writeString(Path.of(filename), sb.toString());
    }


}
