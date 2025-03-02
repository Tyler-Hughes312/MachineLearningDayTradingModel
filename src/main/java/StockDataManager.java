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
        public String date;  // Changed to public
        public double open;  // Changed to public
        public double high;  // Changed to public
        public double low;   // Changed to public
        public double close; // Changed to public
        public double volume; // Changed to public

        public StockEntry(String date, double open, double high, double low, double close, double volume) {
            this.date = date;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }

        @Override
        public String toString() {
            return String.format("%s, %.2f, %.2f, %.2f, %.2f, %.0f",
                    date, open, high, low, close, volume);
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
            List<StockEntry> data = processData(manager.getOutputFile());
            System.out.println(toString(data));
            createAndShowCharts(data);
        } catch (IOException e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            System.exit(1);
        }
    }

    private boolean isDataCacheValid() throws IOException {
        if (!jsonFile.exists() || jsonFile.length() == 0) {
            return false;
        }

        // Check if the file was modified within the cache duration
        long lastModified = jsonFile.lastModified();
        long currentTime = System.currentTimeMillis();
        long hoursSinceModified = (currentTime - lastModified) / (60 * 60 * 1000);

        return hoursSinceModified < CACHE_DURATION_HOURS;
    }

    public List<StockEntry> loadCachedData() throws IOException {
        // Always try to load data if the file exists, even if it's not current
        if (!jsonFile.exists() || jsonFile.length() == 0) {
            return null;
        }
        
        List<StockEntry> data = processData(jsonFile.toString());
        if (!data.isEmpty()) {
            System.out.println("Loaded " + data.size() + " entries for " + jsonFile.getName() + 
                             ", most recent date: " + data.get(0).date);
        }
        return data;
    }

    public List<StockEntry> fetchAndSaveStockData(String symbol) throws IOException {
        try {
            // Try to fetch from API first
            String response = fetchDataFromAPI(symbol);
            
            // Check if response contains rate limit message
            if (response != null && response.contains("standard API rate limit")) {
                this.usingMockData = true;
                List<StockEntry> mockData = generateMockData(symbol);
                saveMockDataToFile(mockData, symbol);
                return mockData;
            }
            
            if (response != null && !response.isEmpty()) {
                this.usingMockData = false;
                saveToFile(response, symbol);
                return processData(getOutputFile());
            } else {
                // If API fails, generate and save mock data
                this.usingMockData = true;
                List<StockEntry> mockData = generateMockData(symbol);
                saveMockDataToFile(mockData, symbol);
                return mockData;
            }
        } catch (Exception e) {
            System.out.println("Using mock data for " + symbol + " due to API error: " + e.getMessage());
            this.usingMockData = true;
            List<StockEntry> mockData = generateMockData(symbol);
            saveMockDataToFile(mockData, symbol);
            return mockData;
        }
    }
    
    private String fetchDataFromAPI(String symbol) throws IOException {
        String urlStr = String.format("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=%s&apikey=%s",
                symbol, apiKey);
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        
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
        List<StockEntry> entries = processData(jsonFile.toString());
        try (FileWriter writer = new FileWriter(csvFile)) {
            writer.write("Date,Open,High,Low,Close,Volume\n"); // CSV header
            for (StockEntry entry : entries) {
                writer.write(String.format("%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                    entry.date, entry.open, entry.high, entry.low, entry.close, entry.volume));
            }
        }
    }
    
    private List<StockEntry> generateMockData(String symbol) {
        List<StockEntry> mockData = new ArrayList<>();
        Random random = new Random();
        double basePrice = 100.0 + random.nextDouble() * 900.0; // Random base price between 100 and 1000
        
        // Generate 100 days of mock data
        for (int i = 0; i < 100; i++) {
            double dailyVariation = (random.nextDouble() - 0.5) * 5; // -2.5% to +2.5% daily change
            double open = basePrice * (1 + dailyVariation/100);
            double close = open * (1 + (random.nextDouble() - 0.5)/50); // Small variation from open
            double high = Math.max(open, close) * (1 + random.nextDouble()/100);
            double low = Math.min(open, close) * (1 - random.nextDouble()/100);
            long volume = 100000 + random.nextInt(900000); // Random volume between 100k and 1M
            
            // Create date for this entry (going backwards from today)
            LocalDate date = LocalDate.now().minusDays(i);
            
            mockData.add(new StockEntry(
                date.toString(),
                open,
                high,
                low,
                close,
                volume
            ));
            
            // Update base price for next iteration
            basePrice = close;
        }
        
        return mockData;
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

    public static List<StockEntry> processData(String filePath) throws IOException {
        List<StockEntry> entries = new ArrayList<>();
        
        try (FileReader reader = new FileReader(filePath)) {
            JsonObject json = new Gson().fromJson(reader, JsonObject.class);
            JsonObject timeSeries = json.getAsJsonObject("Time Series (Daily)");
            
            if (timeSeries != null) {
                timeSeries.entrySet().forEach(entry -> {
                    String date = entry.getKey();
                    JsonObject dailyData = entry.getValue().getAsJsonObject();
                    
                    try {
                        entries.add(new StockEntry(
                            date,
                            Double.parseDouble(dailyData.get("1. open").getAsString()),
                            Double.parseDouble(dailyData.get("2. high").getAsString()),
                            Double.parseDouble(dailyData.get("3. low").getAsString()),
                            Double.parseDouble(dailyData.get("4. close").getAsString()),
                            (long) Double.parseDouble(dailyData.get("5. volume").getAsString())
                        ));
                    } catch (NumberFormatException e) {
                        System.err.println("Error parsing number for date " + date + ": " + e.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            System.err.println("Error processing data file: " + e.getMessage());
            throw e;
        }
        
        // Sort by date in descending order (most recent first)
        entries.sort((a, b) -> b.date.compareTo(a.date));
        return entries;
    }

    public static String toString(List<StockEntry> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Date, Open, High, Low, Close, Volume\n"); // Add header
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