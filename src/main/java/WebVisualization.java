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
import java.util.Arrays;

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
                                if (prediction != null) {  // Remove the success check since we don't add it anymore
                                    predictions.add(prediction);
                                }
                            } catch (Exception e) {
                                System.err.println("Error getting prediction for a stock: " + e.getMessage());
                            }
                        }

                        executor.shutdown();
                        
                        // Sort predictions by predicted change
                        predictions.sort((a, b) -> {
                            Map<String, Object> aSignals = (Map<String, Object>) a.get("tradingSignals");
                            Map<String, Object> bSignals = (Map<String, Object>) b.get("tradingSignals");
                            double aChange = aSignals != null ? ((Number) aSignals.get("predictedChange")).doubleValue() : 0.0;
                            double bChange = bSignals != null ? ((Number) bSignals.get("predictedChange")).doubleValue() : 0.0;
                            return Double.compare(Math.abs(bChange), Math.abs(aChange)); // Sort by absolute change
                        });

                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("predictions", predictions);
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
        
        // Create thread pools for different tasks
        ExecutorService apiExecutor = Executors.newFixedThreadPool(3);  // 3 threads for API calls
        ExecutorService processingExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());  // CPU-bound tasks
        
        CountDownLatch latch = new CountDownLatch(COMMON_STOCKS.length);
        final AtomicInteger processedCount = new AtomicInteger(0);
        
        // Group stocks into batches of 5 to respect rate limits while allowing parallel processing
        List<List<String>> batches = new ArrayList<>();
        for (int i = 0; i < COMMON_STOCKS.length; i += 5) {
            int end = Math.min(i + 5, COMMON_STOCKS.length);
            batches.add(Arrays.asList(Arrays.copyOfRange(COMMON_STOCKS, i, end)));
        }

        for (List<String> batch : batches) {
            // Process each batch
            for (String symbol : batch) {
                apiExecutor.submit(() -> {
                    try {
                        int count = processedCount.incrementAndGet();
                        System.out.println("Processing " + symbol + " (" + count + "/" + COMMON_STOCKS.length + ")...");
                        StockDataManager stockManager = new StockDataManager(apiKey);
                        stockManager.setSymbol(symbol);
                        
                        try {
                            // Fetch data
                            List<StockDataManager.StockEntry> data = stockManager.fetchAndSaveStockData(symbol);
                            
                            if (data != null && !data.isEmpty()) {
                                stockDataCache.put(symbol, data);
                                
                                // Submit model training to processing executor
                                processingExecutor.submit(() -> {
                                    try {
                                        Model model = new Model();
                                        model.prepareData(data);
                                        model.trainModel();
                                        modelCache.put(symbol, model);
                                        System.out.println("Successfully loaded and processed data for " + symbol);
                                    } catch (Exception e) {
                                        System.err.println("Error training model for " + symbol + ": " + e.getMessage());
                                    }
                                });
                            }
                            
                            // Shorter wait time between API calls within a batch
                            Thread.sleep(5000);
                            
                        } catch (Exception e) {
                            System.err.println("Error processing " + symbol + ": " + e.getMessage());
                            if (e.getMessage() != null && e.getMessage().contains("rate limit")) {
                                System.out.println("Rate limit hit, waiting 30 seconds before next request...");
                                Thread.sleep(30000);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Error processing " + symbol + ": " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait between batches to respect rate limits
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // Wait for all operations to complete or timeout after 15 minutes
        try {
            if (!latch.await(15, TimeUnit.MINUTES)) {
                System.err.println("Timeout waiting for data loading");
            }
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for data loading");
            Thread.currentThread().interrupt();
        }

        // Shutdown executors
        apiExecutor.shutdown();
        processingExecutor.shutdown();
        try {
            if (!apiExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                apiExecutor.shutdownNow();
            }
            if (!processingExecutor.awaitTermination(5, TimeUnit.MINUTES)) {
                processingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            apiExecutor.shutdownNow();
            processingExecutor.shutdownNow();
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
        Model model = modelCache.get(symbol);

        // If no data in cache or no model, try to fetch and process new data
        if (data == null || data.isEmpty() || model == null) {
            try {
                StockDataManager stockManager = new StockDataManager(apiKey);
                stockManager.setSymbol(symbol);
                data = stockManager.fetchAndSaveStockData(symbol);
                
                if (data == null || data.isEmpty()) {
                    System.out.println("No data available for " + symbol + ", using mock data");
                    data = StockDataManager.generateMockData(symbol);
                }
                
                stockDataCache.put(symbol, data);
                model = new Model();
                model.prepareData(data);
                model.trainModel();
                modelCache.put(symbol, model);
            } catch (Exception e) {
                System.out.println("Error fetching data for " + symbol + ", using mock data: " + e.getMessage());
                data = StockDataManager.generateMockData(symbol);
                stockDataCache.put(symbol, data);
                model = new Model();
                model.prepareData(data);
                model.trainModel();
                modelCache.put(symbol, model);
            }
        }

        // Get last entry (most recent data)
        StockDataManager.StockEntry lastEntry = data.get(0); // Data is in reverse chronological order
        
        // Check if market is closed
        java.time.LocalTime currentTime = java.time.LocalTime.now();
        java.time.LocalTime marketOpen = java.time.LocalTime.of(9, 30);  // Market opens at 9:30 AM
        java.time.LocalTime marketClose = java.time.LocalTime.of(16, 0); // Market closes at 4:00 PM
        boolean isMarketClosed = currentTime.isAfter(marketClose) || currentTime.isBefore(marketOpen);
        
        // Get predictions
        double predictedClose = model.predictNextDayPrice(lastEntry, data);
        if (Double.isNaN(predictedClose)) {
            throw new Exception("Invalid prediction value for symbol: " + symbol);
        }
        
        // Calculate predicted values
        double predictedOpen = lastEntry.close; // Use current price as open
        double predictedHigh = Math.max(predictedOpen, predictedClose) * 1.01;   // Estimate 1% higher
        double predictedLow = Math.min(predictedOpen, predictedClose) * 0.99;    // Estimate 1% lower
        
        // Create response map
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("symbol", symbol);
        response.put("date", lastEntry.date);
        response.put("lastClose", lastEntry.close);
        response.put("todayOpen", lastEntry.open); // Always use actual open price
        response.put("currentPrice", lastEntry.close); // Use actual current price
        response.put("predictedHigh", predictedHigh);
        response.put("predictedLow", predictedLow);
        response.put("predictedClose", predictedClose);
        response.put("volume", lastEntry.volume);
        
        // Calculate trading signals
        Map<String, Object> tradingSignals = new HashMap<>();
        
        // Calculate price changes
        double openToCloseChange = ((lastEntry.close - lastEntry.open) / lastEntry.open) * 100;
        double currentToPredictedChange = ((predictedClose - lastEntry.close) / lastEntry.close) * 100;
        
        // If market is closed, predict next day's open
        if (isMarketClosed) {
            double nextDayPredictedOpen = predictedClose; // Use predicted close as next day's open
            response.put("nextDayPredictedOpen", nextDayPredictedOpen);
            tradingSignals.put("nextDayPredictedOpen", nextDayPredictedOpen);
            
            // Calculate next day's predicted movement
            double nextDayPredictedChange = ((nextDayPredictedOpen - lastEntry.close) / lastEntry.close) * 100;
            tradingSignals.put("nextDayPredictedChange", nextDayPredictedChange);
        }
        
        // Use the more significant change for the main prediction
        double percentChange = isMarketClosed ? 
            ((predictedClose - lastEntry.close) / lastEntry.close) * 100 : // Next day's movement when market closed
            Math.abs(openToCloseChange) > Math.abs(currentToPredictedChange) ? 
                openToCloseChange : currentToPredictedChange;
        
        // Add data source information
        tradingSignals.put("dataSource", lastEntry.isMockData ? "Mock Data" : "Real Data");
        
        // Risk management calculations
        double volatility = Math.abs(predictedHigh - predictedLow) / predictedOpen * 100;
        double stopLossPercent = Math.max(2.0, volatility * 0.5); // Minimum 2% stop loss
        double takeProfitPercent = Math.max(stopLossPercent * 1.5, Math.abs(percentChange)); // Minimum 1.5:1 reward:risk
        
        tradingSignals.put("stopLossPercent", stopLossPercent);
        tradingSignals.put("takeProfitPercent", takeProfitPercent);
        tradingSignals.put("riskRewardRatio", takeProfitPercent / stopLossPercent);
        tradingSignals.put("predictedChange", percentChange);
        tradingSignals.put("openToCloseChange", openToCloseChange);
        tradingSignals.put("currentToPredictedChange", currentToPredictedChange);
        tradingSignals.put("confidence", 0.85); // Default confidence value
        
        // Determine sentiment and recommendation based on both changes
        String sentiment;
        String recommendation;
        if (percentChange > 1.0) {
            sentiment = "Bullish";
            recommendation = "Consider Buy";
        } else if (percentChange < -1.0) {
            sentiment = "Bearish";
            recommendation = "Consider Sell";
        } else {
            sentiment = "Neutral";
            recommendation = "Hold";
        }
        
        tradingSignals.put("sentiment", sentiment);
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