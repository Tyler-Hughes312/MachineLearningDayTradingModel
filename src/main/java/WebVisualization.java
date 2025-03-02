import static spark.Spark.*;
import com.google.gson.Gson;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class WebVisualization {
    private static final String CONFIG_FILE = "config.properties";
    private static String apiKey;
    private static final Map<String, List<StockDataManager.StockEntry>> stockDataCache = new ConcurrentHashMap<>();
    private static final Map<String, Model> modelCache = new ConcurrentHashMap<>();
    private static final String[] COMMON_STOCKS = {
        "AAPL", "MSFT", "GOOGL", "AMZN", "NVDA", "META", "BRK-B", "LLY", "AVGO", "JPM",
        "V", "XOM", "ORCL", "MA", "HD", "CVX", "MRK", "ABBV", "KO", "PEP", "BAC", "COST",
        "MCD", "TMO", "CSCO", "CRM", "ACN", "ADBE", "AMD", "NFLX"
    };
    private static final StockDataManager stockDataManager;
    private static final EToroIntegration etoroIntegration;

    static {
        try {
            stockDataManager = new StockDataManager();
            etoroIntegration = new EToroIntegration();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize managers", e);
        }
    }

    public static void main(String[] args) {
        int port = 8080;
        boolean serverStarted = false;
        
        while (!serverStarted && port < 8090) {  // Try up to port 8089
            try {
                port(port);
                staticFiles.location("/public");
                
                // Load configuration
                loadConfiguration();
                
                // Pre-fetch all stock data
                prefetchStockData();
                
                // Enable CORS
                enableCORS();
                
                // Add error handler for 404 Not Found
                notFound((req, res) -> {
                    res.type("application/json");
                    Map<String, Object> error = new HashMap<>();
                    error.put("success", false);
                    error.put("error", "Resource not found: " + req.url());
                    return new Gson().toJson(error);
                });

                // Add general error handler
                internalServerError((req, res) -> {
                    res.type("application/json");
                    Map<String, Object> error = new HashMap<>();
                    error.put("success", false);
                    error.put("error", "Internal server error occurred");
                    return new Gson().toJson(error);
                });
                
                // Define routes
                get("/", (req, res) -> {
                    System.out.println("Received request for /");
                    res.redirect("index.html");
                    return null;
                });

                get("/index.html", (req, res) -> {
                    System.out.println("Serving index.html");
                    try (InputStream is = WebVisualization.class.getClassLoader().getResourceAsStream("public/index.html")) {
                        if (is == null) {
                            System.err.println("Could not find index.html in resources");
                            return createErrorResponse("Could not find index.html");
                        }
                        String content = new String(is.readAllBytes());
                        res.type("text/html");
                        return content;
                    } catch (IOException e) {
                        System.err.println("Error reading index.html: " + e.getMessage());
                        return createErrorResponse("Error reading index.html");
                    }
                });

                get("/top-predictions", (req, res) -> {
                    res.type("application/json");
                    
                    try {
                        if (apiKey == null || apiKey.trim().isEmpty()) {
                            return createErrorResponse("API key is not configured");
                        }

                        List<Map<String, Object>> predictions = new ArrayList<>();
                        ExecutorService executor = Executors.newFixedThreadPool(5); // Use 5 threads
                        List<Future<Map<String, Object>>> futures = new ArrayList<>();

                        // Submit tasks for each stock
                        for (String symbol : COMMON_STOCKS) {
                            futures.add(executor.submit(() -> getPredictionForStock(symbol)));
                        }

                        // Collect results
                        for (Future<Map<String, Object>> future : futures) {
                            try {
                                Map<String, Object> prediction = future.get(30, TimeUnit.SECONDS);
                                if (prediction != null && prediction.containsKey("success") && (Boolean)prediction.get("success")) {
                                    predictions.add(prediction);
                                }
                            } catch (Exception e) {
                                System.err.println("Error getting prediction for a stock: " + e.getMessage());
                            }
                        }

                        executor.shutdown();
                        
                        // Sort predictions by predicted change (descending)
                        predictions.sort((a, b) -> {
                            double changeA = (double) a.get("predictedChange");
                            double changeB = (double) b.get("predictedChange");
                            return Double.compare(changeB, changeA);
                        });

                        // Take top 20
                        List<Map<String, Object>> top20 = predictions.size() > 20 ? 
                            predictions.subList(0, 20) : predictions;

                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("predictions", top20);
                        return new Gson().toJson(response);
                        
                    } catch (Exception e) {
                        return createErrorResponse("Error processing request: " + e.getMessage());
                    }
                });
                
                post("/predict", (req, res) -> {
                    res.type("application/json");
                    
                    // Get the stock symbol from the request
                    String symbol = req.queryParams("symbol");
                    if (symbol == null || symbol.trim().isEmpty()) {
                        return createErrorResponse("Stock symbol is required");
                    }
                    
                    try {
                        Map<String, Object> prediction = getPredictionForStock(symbol);
                        return new Gson().toJson(prediction);
                    } catch (Exception e) {
                        return createErrorResponse("Error processing request: " + e.getMessage());
                    }
                });

                // Add eToro login endpoint
                post("/etoro-login", (req, res) -> {
                    res.type("application/json");
                    JsonObject jsonResponse = new JsonObject();
                    
                    try {
                        String body = req.body();
                        JsonObject jsonRequest = JsonParser.parseString(body).getAsJsonObject();
                        String username = jsonRequest.get("username").getAsString();
                        String password = jsonRequest.get("password").getAsString();
                        
                        if (etoroIntegration.login(username, password)) {
                            double balance = etoroIntegration.getPortfolioBalance();
                            jsonResponse.addProperty("success", true);
                            jsonResponse.addProperty("balance", balance);
                        } else {
                            jsonResponse.addProperty("success", false);
                            jsonResponse.addProperty("error", "Invalid credentials");
                        }
                    } catch (Exception e) {
                        jsonResponse.addProperty("success", false);
                        jsonResponse.addProperty("error", "Server error: " + e.getMessage());
                    }
                    
                    return jsonResponse.toString();
                });

                // Add eToro trade endpoint
                post("/etoro-trade", (req, res) -> {
                    res.type("application/json");
                    JsonObject jsonResponse = new JsonObject();
                    
                    try {
                        String body = req.body();
                        JsonObject jsonRequest = JsonParser.parseString(body).getAsJsonObject();
                        String symbol = jsonRequest.get("symbol").getAsString();
                        boolean isBuy = jsonRequest.get("isBuy").getAsBoolean();
                        
                        // Default to 1 share for manual trades
                        if (etoroIntegration.executeTrade(symbol, 1, isBuy)) {
                            double newBalance = etoroIntegration.getPortfolioBalance();
                            jsonResponse.addProperty("success", true);
                            jsonResponse.addProperty("newBalance", newBalance);
                        } else {
                            jsonResponse.addProperty("success", false);
                            jsonResponse.addProperty("error", "Trade execution failed");
                        }
                    } catch (Exception e) {
                        jsonResponse.addProperty("success", false);
                        jsonResponse.addProperty("error", "Server error: " + e.getMessage());
                    }
                    
                    return jsonResponse.toString();
                });

                // Add eToro automated trade endpoint
                post("/etoro-auto-trade", (req, res) -> {
                    res.type("application/json");
                    JsonObject jsonResponse = new JsonObject();
                    
                    try {
                        String body = req.body();
                        JsonObject jsonRequest = JsonParser.parseString(body).getAsJsonObject();
                        String symbol = jsonRequest.get("symbol").getAsString();
                        double predictedChange = jsonRequest.get("predictedChange").getAsDouble();
                        
                        // Use 80% confidence for automated trades
                        if (etoroIntegration.executeAutomatedTrade(symbol, predictedChange, 0.8)) {
                            double newBalance = etoroIntegration.getPortfolioBalance();
                            jsonResponse.addProperty("success", true);
                            jsonResponse.addProperty("newBalance", newBalance);
                        } else {
                            jsonResponse.addProperty("success", false);
                            jsonResponse.addProperty("error", "Automated trade execution failed");
                        }
                    } catch (Exception e) {
                        jsonResponse.addProperty("success", false);
                        jsonResponse.addProperty("error", "Server error: " + e.getMessage());
                    }
                    
                    return jsonResponse.toString();
                });
                
                serverStarted = true;
                System.out.println("Server started on port " + port);
            } catch (Exception e) {
                System.out.println("Port " + port + " is in use, trying next port...");
                port++;
            }
        }
        
        if (!serverStarted) {
            System.err.println("Could not start server on any port between 8080 and 8089");
            System.exit(1);
        }
    }

    private static void prefetchStockData() {
        System.out.println("Loading stock data for all symbols...");
        ExecutorService executor = Executors.newFixedThreadPool(1); // Use single thread to respect rate limits
        CountDownLatch latch = new CountDownLatch(COMMON_STOCKS.length);
        final AtomicInteger processedCount = new AtomicInteger(0);

        for (String symbol : COMMON_STOCKS) {
            executor.submit(() -> {
                try {
                    int count = processedCount.incrementAndGet();
                    System.out.println("Processing " + symbol + " (" + count + "/" + COMMON_STOCKS.length + ")...");
                    StockDataManager stockManager = new StockDataManager(apiKey);
                    stockManager.setSymbol(symbol);
                    
                    try {
                        stockManager.fetchAndSaveStockData(symbol);
                        // Wait 15 seconds between API calls to respect rate limits
                        Thread.sleep(15000);
                        List<StockDataManager.StockEntry> data = StockDataManager.processData(stockManager.getOutputFile());
                        
                        if (data != null && !data.isEmpty()) {
                            stockDataCache.put(symbol, data);
                            // Pre-train the model
                            Model model = new Model();
                            model.prepareData(data);
                            model.trainModel();
                            modelCache.put(symbol, model);
                            System.out.println("Successfully loaded and processed data for " + symbol);
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing " + symbol + ": " + e.getMessage());
                        // If it's a rate limit error, wait longer
                        if (e.getMessage() != null && e.getMessage().contains("rate limit")) {
                            System.out.println("Rate limit hit, waiting 60 seconds before next request...");
                            Thread.sleep(60000);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error processing " + symbol + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all operations to complete or timeout after 30 minutes
        try {
            if (!latch.await(30, TimeUnit.MINUTES)) {
                System.err.println("Timeout waiting for data loading");
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for data loading");
            Thread.currentThread().interrupt();
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("Data loading completed. Cached data for " + stockDataCache.size() + " symbols");
        if (stockDataCache.size() > 0) {
            System.out.println("\nAvailable stocks:");
            stockDataCache.forEach((symbol, data) -> {
                System.out.println(symbol + ": " + data.size() + " entries, latest date: " + data.get(0).date);
            });
        }
    }

    private static Map<String, Object> getPredictionForStock(String symbol) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new Exception("API key is not configured");
        }

        // Use cached data and model if available
        List<StockDataManager.StockEntry> data = stockDataCache.get(symbol);
        if (data == null || data.isEmpty()) {
            throw new Exception("No data available for symbol: " + symbol);
        }

        Model model = modelCache.get(symbol);
        if (model == null) {
            throw new Exception("No model available for symbol: " + symbol);
        }

        // Get last entry (most recent data)
        StockDataManager.StockEntry lastEntry = data.get(0); // Data is in reverse chronological order
        
        // Get predictions for today and tomorrow
        double todayPrediction = model.predictNextDayPrice(lastEntry, data);
        
        // Calculate today's predicted values
        double todayPredictedOpen = todayPrediction * 0.9995; // Slight adjustment for open
        double todayPredictedHigh = todayPrediction * 1.01;   // Estimate 1% higher
        double todayPredictedLow = todayPrediction * 0.99;    // Estimate 1% lower
        double todayPredictedClose = todayPrediction;
        double todayPredictedVolume = lastEntry.volume * 1.05; // Estimate 5% volume increase
        
        // Get tomorrow's prediction using today's predicted values
        double tomorrowPrediction = model.predictNextDayPrice(new StockDataManager.StockEntry(
            lastEntry.date,
            todayPredictedOpen,
            todayPredictedHigh,
            todayPredictedLow,
            todayPredictedClose,
            todayPredictedVolume
        ), data);

        // Calculate percentage changes
        double todayChange = ((todayPredictedClose - lastEntry.close) / lastEntry.close) * 100;
        double tomorrowChange = ((tomorrowPrediction - todayPredictedClose) / todayPredictedClose) * 100;
        
        // Calculate volatility (using high-low range as percentage of opening price)
        double todayVolatility = ((todayPredictedHigh - todayPredictedLow) / todayPredictedOpen) * 100;
        
        // Calculate trading volume change
        double volumeChange = ((todayPredictedVolume - lastEntry.volume) / lastEntry.volume) * 100;

        // Create response matching frontend expectations
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("symbol", symbol);
        response.put("date", lastEntry.date);
        response.put("isMockData", stockDataManager.isUsingMockData()); // Add mock data status
        
        // Historical data (last known values)
        response.put("lastClose", lastEntry.close);
        response.put("lastOpen", lastEntry.open);
        response.put("lastHigh", lastEntry.high);
        response.put("lastLow", lastEntry.low);
        response.put("lastVolume", lastEntry.volume);
        
        // Today's predicted values
        response.put("todayOpen", todayPredictedOpen);
        response.put("todayHigh", todayPredictedHigh);
        response.put("todayLow", todayPredictedLow);
        response.put("todayClose", todayPredictedClose);
        response.put("todayVolume", todayPredictedVolume);
        response.put("todayChange", todayChange);
        response.put("todayVolatility", todayVolatility);
        response.put("volumeChange", volumeChange);
        
        // Current price (estimated as weighted average between high and low)
        double currentPrice = (todayPredictedHigh * 0.6 + todayPredictedLow * 0.4);
        response.put("currentPrice", currentPrice);
        
        // Tomorrow's predictions
        response.put("tomorrowPrice", tomorrowPrediction);
        response.put("tomorrowChange", tomorrowChange);
        
        // Market sentiment indicators
        boolean bullishSignal = todayChange > 0 && tomorrowChange > 0;
        boolean bearishSignal = todayChange < 0 && tomorrowChange < 0;
        response.put("marketSentiment", bullishSignal ? "Bullish" : (bearishSignal ? "Bearish" : "Neutral"));
        
        // Enhanced trading signals and recommendations
        Map<String, Object> tradingSignals = new HashMap<>();
        
        // Calculate confidence score (0-1) based on multiple factors
        double trendConfidence = Math.min(1.0, Math.abs(tomorrowChange) / 5.0); // Higher change = higher confidence
        double volumeConfidence = Math.min(1.0, Math.abs(volumeChange) / 50.0); // Volume spike indicates confidence
        double volatilityImpact = Math.max(0, 1 - (todayVolatility / 10.0)); // Lower volatility = higher confidence
        
        double overallConfidence = (trendConfidence * 0.5 + volumeConfidence * 0.3 + volatilityImpact * 0.2);
        
        // Calculate recommended position size
        double maxPositionSize = 0.1; // Maximum 10% of portfolio per trade
        double recommendedPositionSizePercent = maxPositionSize * overallConfidence;
        
        // Calculate stop loss and take profit levels
        double stopLossPercent = Math.max(2.0, todayVolatility * 0.5); // Minimum 2% stop loss
        double takeProfitPercent = Math.max(stopLossPercent * 1.5, Math.abs(tomorrowChange)); // Minimum 1.5:1 reward:risk
        
        tradingSignals.put("volumeAlert", Math.abs(volumeChange) > 20);
        tradingSignals.put("volatilityAlert", todayVolatility > 5);
        tradingSignals.put("trendStrength", Math.abs(todayChange));
        tradingSignals.put("overallConfidence", overallConfidence);
        tradingSignals.put("recommendedPositionSize", recommendedPositionSizePercent);
        tradingSignals.put("stopLossPercent", stopLossPercent);
        tradingSignals.put("takeProfitPercent", takeProfitPercent);
        tradingSignals.put("riskRewardRatio", takeProfitPercent / stopLossPercent);
        
        String recommendation;
        if (bullishSignal && overallConfidence > 0.6) {
            recommendation = "Strong Buy";
        } else if (bullishSignal && overallConfidence > 0.3) {
            recommendation = "Consider Buy";
        } else if (bearishSignal && overallConfidence > 0.6) {
            recommendation = "Strong Sell";
        } else if (bearishSignal && overallConfidence > 0.3) {
            recommendation = "Consider Sell";
        } else {
            recommendation = "Hold";
        }
        tradingSignals.put("recommendation", recommendation);
        
        response.put("tradingSignals", tradingSignals);

        return response;
    }
    
    private static void loadConfiguration() {
        Properties props = new Properties();
        
        // First try loading from classpath
        try (InputStream inputStream = WebVisualization.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (inputStream != null) {
                props.load(inputStream);
                apiKey = props.getProperty("api.key");
                if (apiKey != null && !apiKey.trim().isEmpty() && !apiKey.equals("YOUR_API_KEY_HERE")) {
                    // Mask the API key in logs
                    String maskedKey = apiKey.substring(0, Math.min(4, apiKey.length())) + "...";
                    System.out.println("Successfully loaded API key from classpath: " + maskedKey);
                    return;
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading from classpath: " + e.getMessage());
        }

        // If classpath loading fails, try file system
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            configFile = new File("src/main/resources/" + CONFIG_FILE);
        }

        if (!configFile.exists()) {
            System.err.println("Could not find " + CONFIG_FILE + " in either the classpath, current directory, or resources directory.");
            System.err.println("Please ensure " + CONFIG_FILE + " exists with your API key.");
            System.exit(1);
        }

        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
            apiKey = props.getProperty("api.key");
            
            if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("YOUR_API_KEY_HERE")) {
                System.err.println("Invalid API key in " + configFile.getPath());
                System.err.println("Please set a valid API key in the config file.");
                System.exit(1);
            }
            
            // Mask the API key in logs
            String maskedKey = apiKey.substring(0, Math.min(4, apiKey.length())) + "...";
            System.out.println("Successfully loaded API key from " + configFile.getPath() + ": " + maskedKey);
        } catch (IOException e) {
            System.err.println("Error loading configuration from " + configFile.getPath() + ": " + e.getMessage());
            System.exit(1);
        }
    }
    
    private static String createErrorResponse(String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("success", false);
        error.put("error", message);
        return new Gson().toJson(error);
    }
    
    private static void enableCORS() {
        options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return "OK";
        });

        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Request-Method", "GET,POST,PUT,DELETE,OPTIONS");
            response.header("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Requested-With,Content-Length,Accept,Origin");
            response.type("application/json");
        });
    }
} 