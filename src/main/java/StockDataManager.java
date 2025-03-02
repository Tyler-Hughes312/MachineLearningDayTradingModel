import com.crazzyghost.alphavantage.AlphaVantage;
import com.crazzyghost.alphavantage.Config;
import com.crazzyghost.alphavantage.parameters.OutputSize;
import com.crazzyghost.alphavantage.timeseries.response.StockUnit;
import com.crazzyghost.alphavantage.timeseries.response.TimeSeriesResponse;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.*;
import org.jfree.data.xy.XYDataset;
import weka.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import javax.sql.DataSource;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.Calendar;
import java.time.LocalDate;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;

public class StockDataManager {
    private static final String CONFIG_FILE = "config.properties";
    private static final String API_KEY_FILE = "AlphaVantageAPI.txt";
    private File csvFile;
    private File jsonFile;
    private String apiKey;
    private static final long CACHE_DURATION_HOURS = 24; // Cache duration in hours
    private String symbol;
    private boolean usingMockData;  // New field to track mock data usage
    
    public StockDataManager() throws IOException {
        loadApiKey();
        this.usingMockData = false;
    }
    
    public StockDataManager(String apiKey) {
        this.apiKey = apiKey;
        this.usingMockData = false;
    }
    
    private void loadApiKey() throws IOException {
        try {
            // Try to load from AlphaVantageAPI.txt first
            File apiKeyFile = new File(API_KEY_FILE);
            if (apiKeyFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(apiKeyFile))) {
                    this.apiKey = reader.readLine().trim();
                    return;
                }
            }
            
            // Fallback to config.properties if API key file doesn't exist
            Properties props = new Properties();
            try (InputStream input = new FileInputStream(CONFIG_FILE)) {
                props.load(input);
                this.apiKey = props.getProperty("api.key");
                if (this.apiKey == null || this.apiKey.trim().isEmpty()) {
                    throw new IOException("API key not found in configuration");
                }
            }
        } catch (IOException e) {
            System.err.println("Error loading API key: " + e.getMessage());
            throw e;
        }
    }
    
    public void setSymbol(String symbol) {
        this.symbol = symbol;
        this.csvFile = new File("data/csv/" + symbol + "_daily.csv");
        this.jsonFile = new File("data/json/" + symbol + "_daily.json");
    }
    
    public String getOutputFile() {
        return csvFile.toString();
    }

    public boolean isUsingMockData() {
        return this.usingMockData;
    }

    // Inner class to represent a stock entry
    public static class StockEntry {
        public String date;
        public double open;
        public double high;
        public double low;
        public double close;
        public double volume;
        public boolean isMockData;
        public double predictedClose;
        public int rank;                 // Added for ranking
        public double change;            // Added for price change
        public String sentiment;         // Added for market sentiment
        public String recommendation;    // Added for trading recommendation
        public double confidence;        // Added for prediction confidence

        public StockEntry(String date, double open, double high, double low, double close, double volume) {
            this(date, open, high, low, close, volume, false);
        }

        public StockEntry(String date, double open, double high, double low, double close, double volume, boolean isMockData) {
            this(date, open, high, low, close, volume, isMockData, Double.NaN);
        }

        public StockEntry(String date, double open, double high, double low, double close, double volume, boolean isMockData, double predictedClose) {
            this(date, open, high, low, close, volume, isMockData, predictedClose, 0, 0.0, "Unknown", "Unknown", 0.0);
        }

        public StockEntry(String date, double open, double high, double low, double close, double volume, 
                         boolean isMockData, double predictedClose, int rank, double change,
                         String sentiment, String recommendation, double confidence) {
            this.date = date;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
            this.isMockData = isMockData;
            this.predictedClose = predictedClose;
            this.rank = rank;
            this.change = change;
            this.sentiment = sentiment;
            this.recommendation = recommendation;
            this.confidence = confidence;
        }

        @Override
        public String toString() {
            String predictionStr = Double.isNaN(predictedClose) ? "N/A" : String.format("%.2f", predictedClose);
            return String.format("%d, %s, %.2f, %.2f, %.2f%%, %s, %s, %.1f%%, %.2f",
                    rank, date, open, close, change, sentiment, recommendation, confidence * 100, predictedClose);
        }
    }

    public static void main(String[] args) throws IOException {
        // Load configuration
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            props.load(fis);
            String apiKey = props.getProperty("api.key");
            if (apiKey == null || apiKey.equals("YOUR_API_KEY_HERE")) {
                System.err.println("Please set your API key in " + CONFIG_FILE);
                System.exit(1);
            }
            
            StockDataManager manager = new StockDataManager(apiKey);
            manager.fetchAndSaveStockData("IBM");
            List<StockEntry> data = processData(manager.getOutputFile(), manager.symbol);
            System.out.println(toString(data));
            createAndShowCharts(data);
        } catch (IOException e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            System.exit(1);
        }
    }

    private boolean isDataCacheValid() throws IOException {
        if (!csvFile.exists() || !jsonFile.exists()) {
            return false;
        }

        // Check if both files have content
        if (csvFile.length() == 0 || jsonFile.length() == 0) {
            return false;
        }

        // Check if the files were modified within the cache duration
        long lastModified = Math.max(csvFile.lastModified(), jsonFile.lastModified());
        long currentTime = System.currentTimeMillis();
        long hoursSinceModified = (currentTime - lastModified) / (60 * 60 * 1000);

        return hoursSinceModified < CACHE_DURATION_HOURS;
    }

    public List<StockEntry> loadCachedData() throws IOException {
        // Always try to load data if the file exists, even if it's not current
        if (!jsonFile.exists() || jsonFile.length() == 0) {
            return null;
        }
        
        List<StockEntry> data = processData(jsonFile.toString(), symbol);
        if (!data.isEmpty()) {
            System.out.println("Loaded " + data.size() + " entries for " + jsonFile.getName() + 
                             ", most recent date: " + data.get(0).date);
        }
        return data;
    }

    public List<StockEntry> fetchAndSaveStockData(String symbol) throws IOException {
        setSymbol(symbol); // Ensure paths are set correctly
        
        // First check if we have valid cached data
        if (isDataCacheValid()) {
            try {
                List<StockEntry> cachedData = loadCachedData();
                if (cachedData != null && !cachedData.isEmpty()) {
                    // Check if the most recent data is from today or yesterday
                    LocalDate mostRecentDate = LocalDate.parse(cachedData.get(0).date);
                    LocalDate today = LocalDate.now();
                    if (mostRecentDate.equals(today) || mostRecentDate.equals(today.minusDays(1))) {
                        System.out.println("Using recent cached data for " + symbol);
                        this.usingMockData = false;
                        return cachedData;
                    }
                }
            } catch (Exception e) {
                System.out.println("Error loading cached data: " + e.getMessage());
            }
        }

        // If we don't have valid cached data, try the API
        try {
            String response = fetchDataFromAPI(symbol);
            
            // Parse the response to check for error messages
            JsonObject root = new Gson().fromJson(response, JsonObject.class);
            
            // Check for API error messages
            if (root.has("Error Message")) {
                System.out.println("API Error for " + symbol + ": " + root.get("Error Message").getAsString());
                return fallbackToMockOrCachedData(symbol);
            }
            
            // Check for rate limit
            if (root.has("Note") && root.get("Note").getAsString().contains("API call frequency")) {
                System.out.println("Rate limit reached for " + symbol + ", checking cached data...");
                List<StockEntry> cachedData = loadCachedData();
                if (cachedData != null && !cachedData.isEmpty()) {
                    System.out.println("Using cached data for " + symbol + " due to rate limit");
                    this.usingMockData = false;
                    return cachedData;
                }
                
                // If no cached data, wait and retry
                System.out.println("No cached data available, waiting 60 seconds before retry...");
                Thread.sleep(60000);
                response = fetchDataFromAPI(symbol);
                root = new Gson().fromJson(response, JsonObject.class);
            }
            
            // Check if we have valid time series data
            if (root.has("Time Series (Daily)")) {
                this.usingMockData = false;
                saveToFile(response, symbol);
                List<StockEntry> data = processData(getOutputFile(), symbol);
                System.out.println("Successfully fetched real data for " + symbol);
                return data;
            } else {
                return fallbackToMockOrCachedData(symbol);
            }
        } catch (Exception e) {
            System.out.println("Error fetching data for " + symbol + ": " + e.getMessage());
            return fallbackToMockOrCachedData(symbol);
        }
    }

    private List<StockEntry> fallbackToMockOrCachedData(String symbol) throws IOException {
        // First try to use cached data if available
        List<StockEntry> cachedData = loadCachedData();
        if (cachedData != null && !cachedData.isEmpty()) {
            System.out.println("Using cached data for " + symbol);
            this.usingMockData = false;
            return cachedData;
        }

        // If no cached data available, use mock data
        System.out.println("No cached data available for " + symbol + ", using mock data");
        this.usingMockData = true;
        List<StockEntry> mockData = generateMockData(symbol);
        saveMockDataToFile(mockData, symbol);
        return mockData;
    }
    
    private String fetchDataFromAPI(String symbol) throws IOException {
        String urlStr = String.format("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=%s&apikey=%s&outputsize=full",
                symbol, apiKey);
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);  // 10 second timeout for connection
        conn.setReadTimeout(10000);     // 10 second timeout for reading
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            
            return response.toString();
        }
    }
    
    private void saveToFile(String data, String symbol) throws IOException {
        // Create directories if they don't exist
        new File("data/json").mkdirs();
        new File("data/csv").mkdirs();
        
        // Delete existing files if they exist
        File jsonFile = new File("data/json/" + symbol + "_daily.json");
        File csvFile = new File("data/csv/" + symbol + "_daily.csv");
        if (jsonFile.exists()) jsonFile.delete();
        if (csvFile.exists()) csvFile.delete();
        
        // Save JSON response
        try (FileWriter writer = new FileWriter(jsonFile)) {
            writer.write(data);
        }
        
        // Process and save as CSV
        List<StockEntry> entries = processData(jsonFile.toString(), symbol);
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("Date,Open,High,Low,Close,Volume\n"); // CSV header
            for (StockEntry entry : entries) {
                writer.write(String.format("%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                    entry.date, entry.open, entry.high, entry.low, entry.close, entry.volume));
            }
        }
    }
    
    public static List<StockEntry> processData(String filePath, String symbol) throws IOException {
        List<StockEntry> entries = new CopyOnWriteArrayList<>();
        
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String content = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            JsonObject root = new Gson().fromJson(content, JsonObject.class);
            
            if (root.has("Time Series (Daily)")) {
                JsonObject timeSeries = root.getAsJsonObject("Time Series (Daily)");
                List<StockEntry> tempEntries = new ArrayList<>();
                
                // First pass: create all entries
                timeSeries.entrySet().forEach(entry -> {
                    String date = entry.getKey();
                    JsonObject dailyData = entry.getValue().getAsJsonObject();
                    
                    try {
                        double open = Double.parseDouble(dailyData.get("1. open").getAsString());
                        double high = Double.parseDouble(dailyData.get("2. high").getAsString());
                        double low = Double.parseDouble(dailyData.get("3. low").getAsString());
                        double close = Double.parseDouble(dailyData.get("4. close").getAsString());
                        double volume = Double.parseDouble(dailyData.get("5. volume").getAsString());
                        
                        // Calculate change percentage
                        double change = ((close - open) / open) * 100;
                        
                        // Determine sentiment based on price movement
                        String sentiment = determineSentiment(change);
                        
                        tempEntries.add(new StockEntry(
                            date,
                            open,
                            high,
                            low,
                            close,
                            volume,
                            false,  // Not mock data
                            Double.NaN,  // Prediction will be added later
                            0,  // Rank will be set later
                            change,
                            sentiment,
                            "Unknown",  // Recommendation will be set later
                            0.0  // Confidence will be set later
                        ));
                    } catch (NumberFormatException e) {
                        System.err.println("Error parsing number for date " + date + ": " + e.getMessage());
                    }
                });

                // Sort entries by date (newest first)
                tempEntries.sort((a, b) -> b.date.compareTo(a.date));

                // Create model and calculate predictions
                Model model = new Model();
                model.prepareData(tempEntries);
                model.trainModel();

                // Second pass: add predictions and calculate recommendations
                for (int i = 0; i < tempEntries.size(); i++) {
                    StockEntry entry = tempEntries.get(i);
                    List<StockEntry> historicalData = tempEntries.subList(0, i + 1);
                    double prediction = model.predictNextDayPrice(entry, historicalData);
                    
                    // Calculate confidence based on historical accuracy
                    double confidence = calculateConfidence(historicalData, model);
                    
                    // Determine recommendation based on prediction and confidence
                    String recommendation = determineRecommendation(entry.close, prediction, confidence);
                    
                    entries.add(new StockEntry(
                        entry.date,
                        entry.open,
                        entry.high,
                        entry.low,
                        entry.close,
                        entry.volume,
                        false,
                        prediction,
                        0,  // Rank will be set later
                        entry.change,
                        entry.sentiment,
                        recommendation,
                        confidence
                    ));
                }

                // Sort by change percentage to determine rank
                entries.sort((a, b) -> Double.compare(Math.abs(b.change), Math.abs(a.change)));
                for (int i = 0; i < entries.size(); i++) {
                    StockEntry entry = entries.get(i);
                    entry.rank = i + 1;
                }
            } else {
                System.out.println("No time series data found for file: " + filePath + ". Generating mock data.");
                return generateMockData(symbol);
            }
        } catch (Exception e) {
            System.err.println("Error processing data file: " + e.getMessage());
            System.out.println("Falling back to mock data generation.");
            return generateMockData(symbol);
        }
        
        return entries;
    }

    private static String determineSentiment(double change) {
        if (change > 2.0) return "Very Bullish";
        if (change > 0.5) return "Bullish";
        if (change < -2.0) return "Very Bearish";
        if (change < -0.5) return "Bearish";
        return "Neutral";
    }

    private static String determineRecommendation(double currentPrice, double predictedPrice, double confidence) {
        if (Double.isNaN(predictedPrice)) return "Hold";
        
        double expectedReturn = ((predictedPrice - currentPrice) / currentPrice) * 100;
        
        if (confidence < 0.5) return "Hold";
        if (expectedReturn > 2.0 && confidence > 0.7) return "Strong Buy";
        if (expectedReturn > 1.0) return "Buy";
        if (expectedReturn < -2.0 && confidence > 0.7) return "Strong Sell";
        if (expectedReturn < -1.0) return "Sell";
        return "Hold";
    }

    private static double calculateConfidence(List<StockEntry> historicalData, Model model) {
        if (historicalData.size() < 2) return 0.0;
        
        int correctPredictions = 0;
        int totalPredictions = 0;
        
        for (int i = 1; i < historicalData.size(); i++) {
            StockEntry previous = historicalData.get(i);
            StockEntry current = historicalData.get(i-1);
            
            if (!Double.isNaN(previous.predictedClose)) {
                double predictedDirection = previous.predictedClose - previous.close;
                double actualDirection = current.close - previous.close;
                
                if ((predictedDirection > 0 && actualDirection > 0) ||
                    (predictedDirection < 0 && actualDirection < 0)) {
                    correctPredictions++;
                }
                totalPredictions++;
            }
        }
        
        return totalPredictions > 0 ? (double) correctPredictions / totalPredictions : 0.0;
    }

    public static List<StockEntry> generateMockData(String symbol) {
        List<StockEntry> mockData = new ArrayList<>();
        Random random = new Random();
        
        // Use different base prices for different symbols
        double basePrice;
        switch(symbol.toUpperCase()) {
            case "AAPL":
                basePrice = 170.0;
                break;
            case "MSFT":
                basePrice = 380.0;
                break;
            case "GOOGL":
                basePrice = 140.0;
                break;
            case "AMZN":
                basePrice = 170.0;
                break;
            case "NVDA":
                basePrice = 720.0;
                break;
            case "META":
                basePrice = 480.0;
                break;
            case "BRK-B":
                basePrice = 360.0;
                break;
            case "LLY":
                basePrice = 740.0;
                break;
            case "AVGO":
                basePrice = 1200.0;
                break;
            case "JPM":
                basePrice = 170.0;
                break;
            case "V":
                basePrice = 270.0;
                break;
            case "XOM":
                basePrice = 105.0;
                break;
            case "ORCL":
                basePrice = 110.0;
                break;
            case "MA":
                basePrice = 470.0;
                break;
            case "HD":
                basePrice = 370.0;
                break;
            case "CVX":
                basePrice = 150.0;
                break;
            case "MRK":
                basePrice = 125.0;
                break;
            case "ABBV":
                basePrice = 170.0;
                break;
            case "KO":
                basePrice = 60.0;
                break;
            case "PEP":
                basePrice = 170.0;
                break;
            case "BAC":
                basePrice = 33.0;
                break;
            case "COST":
                basePrice = 730.0;
                break;
            case "MCD":
                basePrice = 290.0;
                break;
            case "TMO":
                basePrice = 550.0;
                break;
            case "CSCO":
                basePrice = 49.0;
                break;
            case "CRM":
                basePrice = 280.0;
                break;
            case "ACN":
                basePrice = 370.0;
                break;
            case "ADBE":
                basePrice = 550.0;
                break;
            case "AMD":
                basePrice = 170.0;
                break;
            case "NFLX":
                basePrice = 580.0;
                break;
            default:
                basePrice = 100.0 + random.nextDouble() * 900.0; // Random base price between 100 and 1000
                break;
        }
        
        // Generate 100 days of mock data first without predictions
        for (int i = 0; i < 100; i++) {
            double volatility = 0.02; // 2% daily volatility
            double dailyVariation = (random.nextGaussian() * volatility);
            double open = basePrice * (1 + dailyVariation);
            double close = open * (1 + (random.nextGaussian() * volatility));
            double high = Math.max(open, close) * (1 + Math.abs(random.nextGaussian() * volatility));
            double low = Math.min(open, close) * (1 - Math.abs(random.nextGaussian() * volatility));
            long volume = Math.max(50000, 100000 + (long)(random.nextGaussian() * 500000));
            
            // Calculate change percentage
            double change = ((close - open) / open) * 100;
            
            // Determine sentiment based on price movement
            String sentiment = determineSentiment(change);
            
            // Create date for this entry (going backwards from today)
            LocalDate date = LocalDate.now().minusDays(i);
            
            mockData.add(new StockEntry(
                date.toString(),
                open,
                high,
                low,
                close,
                volume,
                true,  // Set isMockData to true
                Double.NaN,  // Prediction will be added later
                0,  // Rank will be set later
                change,
                sentiment,
                "Unknown",  // Recommendation will be set later
                0.0  // Confidence will be set later
            ));
            
            // Update base price for next iteration
            basePrice = close;
        }

        // Sort entries by date (newest first)
        mockData.sort((a, b) -> b.date.compareTo(a.date));

        try {
            // Create model and calculate predictions
            Model model = new Model();
            model.prepareData(mockData);
            model.trainModel();

            // Create new list with predictions and recommendations
            List<StockEntry> mockDataWithPredictions = new ArrayList<>();
            for (int i = 0; i < mockData.size(); i++) {
                StockEntry entry = mockData.get(i);
                List<StockEntry> historicalData = mockData.subList(0, i + 1);
                double prediction = model.predictNextDayPrice(entry, historicalData);
                
                // Calculate confidence based on historical accuracy
                double confidence = calculateConfidence(historicalData, model);
                
                // Determine recommendation based on prediction and confidence
                String recommendation = determineRecommendation(entry.close, prediction, confidence);
                
                mockDataWithPredictions.add(new StockEntry(
                    entry.date,
                    entry.open,
                    entry.high,
                    entry.low,
                    entry.close,
                    entry.volume,
                    true,
                    prediction,
                    0,  // Rank will be set later
                    entry.change,
                    entry.sentiment,
                    recommendation,
                    confidence
                ));
            }

            // Sort by change percentage to determine rank
            mockDataWithPredictions.sort((a, b) -> Double.compare(Math.abs(b.change), Math.abs(a.change)));
            for (int i = 0; i < mockDataWithPredictions.size(); i++) {
                StockEntry entry = mockDataWithPredictions.get(i);
                entry.rank = i + 1;
            }

            return mockDataWithPredictions;
        } catch (Exception e) {
            System.err.println("Error generating predictions for mock data: " + e.getMessage());
            return mockData; // Return data without predictions if model fails
        }
    }

    private void saveMockDataToFile(List<StockEntry> mockData, String symbol) throws IOException {
        // Create directories if they don't exist
        new File("data/json").mkdirs();
        new File("data/csv").mkdirs();
        
        // Delete existing files if they exist
        File jsonFile = new File("data/json/" + symbol + "_daily.json");
        File csvFile = new File("data/csv/" + symbol + "_daily.csv");
        if (jsonFile.exists()) jsonFile.delete();
        if (csvFile.exists()) csvFile.delete();
        
        // Save as JSON
        JsonObject json = new JsonObject();
        JsonObject timeSeries = new JsonObject();
        
        for (StockEntry entry : mockData) {
            JsonObject dailyData = new JsonObject();
            dailyData.addProperty("1. open", entry.open);
            dailyData.addProperty("2. high", entry.high);
            dailyData.addProperty("3. low", entry.low);
            dailyData.addProperty("4. close", entry.close);
            dailyData.addProperty("5. volume", entry.volume);
            timeSeries.add(entry.date, dailyData);
        }
        
        json.add("Time Series (Daily)", timeSeries);
        
        // Save to JSON file
        try (FileWriter writer = new FileWriter(jsonFile)) {
            new Gson().toJson(json, writer);
        }
        
        // Save as CSV
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("Date,Open,High,Low,Close,Volume\n"); // CSV header
            for (StockEntry entry : mockData) {
                writer.write(String.format("%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                    entry.date, entry.open, entry.high, entry.low, entry.close, entry.volume));
            }
        }
    }

    private static String extractSymbolFromPath(String filePath) {
        return new File(filePath).getName().split("_")[0].replaceAll("\\.[^.]+$", "");
    }

    public static String toString(List<StockEntry> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rank, Symbol, Open, Current, Change, Sentiment, Recommendation, Confidence, Predicted Close\n");
        for (StockEntry entry : data) {
            sb.append(entry.toString()).append("\n");
        }
        return sb.toString();
    }

    private static void createAndShowCharts(List<StockEntry> data) {
        // Create datasets
        TimeSeriesCollection priceDataset = createPriceDataset(data);
        TimeSeriesCollection volumeDataset = createVolumeDataset(data);
        TimeSeriesCollection predictionDataset = createPredictionDataset(data);
        
        // Create the price chart
        JFreeChart priceChart = ChartFactory.createTimeSeriesChart(
            "Stock Price Over Time",     // chart title
            "Date",                      // x axis label
            "Price",                     // y axis label
            priceDataset,                // data
            true,                        // include legend
            true,                        // tooltips
            false                        // urls
        );

        // Customize the price chart
        XYPlot pricePlot = (XYPlot) priceChart.getPlot();
        DateAxis priceAxis = (DateAxis) pricePlot.getDomainAxis();
        priceAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));
        
        // Add prediction dataset to price chart
        pricePlot.setDataset(1, predictionDataset);
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, true);
        renderer.setSeriesPaint(0, Color.RED);
        renderer.setSeriesStroke(0, new BasicStroke(2.0f));
        pricePlot.setRenderer(1, renderer);

        // Create the volume chart
        JFreeChart volumeChart = ChartFactory.createTimeSeriesChart(
            "Trading Volume",            // chart title
            "Date",                      // x axis label
            "Volume",                    // y axis label
            volumeDataset,               // data
            true,                        // include legend
            true,                        // tooltips
            false                        // urls
        );

        // Customize volume chart
        XYPlot volumePlot = (XYPlot) volumeChart.getPlot();
        DateAxis volumeAxis = (DateAxis) volumePlot.getDomainAxis();
        volumeAxis.setDateFormatOverride(new SimpleDateFormat("yyyy-MM-dd"));

        // Create the main frame
        JFrame frame = new JFrame("Stock Analysis");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new GridLayout(2, 1));

        // Add charts to the frame
        ChartPanel priceChartPanel = new ChartPanel(priceChart);
        ChartPanel volumeChartPanel = new ChartPanel(volumeChart);
        
        frame.add(priceChartPanel);
        frame.add(volumeChartPanel);

        frame.pack();
        frame.setSize(1024, 768);
        frame.setVisible(true);
    }

    private static TimeSeriesCollection createPriceDataset(List<StockEntry> data) {
        TimeSeries openSeries = new TimeSeries("Open");
        TimeSeries closeSeries = new TimeSeries("Close");
        TimeSeries highSeries = new TimeSeries("High");
        TimeSeries lowSeries = new TimeSeries("Low");

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        
        for (StockEntry entry : data) {
            try {
                Day day = new Day(dateFormat.parse(entry.date));
                openSeries.add(day, entry.open);
                closeSeries.add(day, entry.close);
                highSeries.add(day, entry.high);
                lowSeries.add(day, entry.low);
            } catch (ParseException e) {
                System.err.println("Error parsing date: " + entry.date);
            }
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(openSeries);
        dataset.addSeries(closeSeries);
        dataset.addSeries(highSeries);
        dataset.addSeries(lowSeries);

        return dataset;
    }

    private static TimeSeriesCollection createVolumeDataset(List<StockEntry> data) {
        TimeSeries volumeSeries = new TimeSeries("Volume");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        
        for (StockEntry entry : data) {
            try {
                Day day = new Day(dateFormat.parse(entry.date));
                volumeSeries.add(day, entry.volume);
            } catch (ParseException e) {
                System.err.println("Error parsing date: " + entry.date);
            }
        }

        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(volumeSeries);

        return dataset;
    }

    private static TimeSeriesCollection createPredictionDataset(List<StockEntry> data) {
        TimeSeries predictionSeries = new TimeSeries("Predicted");
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        
        try {
            // Create and train model for predictions
            Model model = new Model();
            model.prepareData(data);
            model.trainModel();
            
            // Generate predictions for the last 30 days
            int startIndex = Math.max(0, data.size() - 30);
            for (int i = startIndex; i < data.size(); i++) {
                StockEntry entry = data.get(i);
                List<StockEntry> historicalData = data.subList(0, i + 1);
                double prediction = model.predictNextDayPrice(entry, historicalData);
                
                if (!Double.isNaN(prediction)) {
                    // Add prediction for the next day
                    Day currentDay = new Day(dateFormat.parse(entry.date));
                    Day nextDay = (Day) currentDay.next();
                    predictionSeries.add(nextDay, prediction);
                }
            }
        } catch (ParseException e) {
            System.err.println("Error parsing date in prediction dataset: " + e.getMessage());
        }
        
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        dataset.addSeries(predictionSeries);
        return dataset;
    }
}