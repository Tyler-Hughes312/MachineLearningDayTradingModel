import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;
import java.util.Properties;
import java.io.FileInputStream;

public class EToroIntegration {
    private static final String BASE_URL = "https://www.etoro.com/api/v1";
    private static final String LOGIN_ENDPOINT = "/login";
    private static final String PORTFOLIO_ENDPOINT = "/portfolio/virtual";
    private static final String TRADE_ENDPOINT = "/trade/virtual";
    
    private String sessionToken;
    private final HttpClient httpClient;
    private final Gson gson;
    private final Properties config;
    
    public EToroIntegration() throws IOException {
        this.httpClient = HttpClients.createDefault();
        this.gson = new Gson();
        this.config = loadConfig();
    }
    
    private Properties loadConfig() throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream("config.properties")) {
            props.load(fis);
            if (!props.containsKey("etoro.username") || !props.containsKey("etoro.password")) {
                throw new IOException("eToro credentials not found in config.properties");
            }
            return props;
        }
    }
    
    public boolean login() {
        return login(config.getProperty("etoro.username"), config.getProperty("etoro.password"));
    }
    
    public boolean login(String username, String password) {
        try {
            Map<String, String> credentials = new HashMap<>();
            credentials.put("username", username);
            credentials.put("password", password);
            
            HttpPost request = new HttpPost(BASE_URL + LOGIN_ENDPOINT);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(gson.toJson(credentials)));
            
            String response = EntityUtils.toString(httpClient.execute(request).getEntity());
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            
            if (jsonResponse.has("token")) {
                this.sessionToken = jsonResponse.get("token").getAsString();
                System.out.println("Successfully logged in to eToro Virtual Portfolio");
                return true;
            }
            
            System.err.println("Login failed: " + jsonResponse.get("message").getAsString());
            return false;
            
        } catch (Exception e) {
            System.err.println("Error during login: " + e.getMessage());
            return false;
        }
    }
    
    public double getPortfolioBalance() {
        try {
            if (sessionToken == null) {
                if (!login()) {
                    throw new IOException("Not logged in");
                }
            }
            
            HttpGet request = new HttpGet(BASE_URL + PORTFOLIO_ENDPOINT);
            request.setHeader("Authorization", "Bearer " + sessionToken);
            
            String response = EntityUtils.toString(httpClient.execute(request).getEntity());
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            
            return jsonResponse.get("balance").getAsDouble();
            
        } catch (Exception e) {
            System.err.println("Error getting portfolio balance: " + e.getMessage());
            return -1;
        }
    }
    
    public boolean executeTrade(String symbol, int quantity, boolean isBuy) {
        try {
            if (sessionToken == null) {
                if (!login()) {
                    throw new IOException("Not logged in");
                }
            }
            
            Map<String, Object> tradeRequest = new HashMap<>();
            tradeRequest.put("symbol", symbol);
            tradeRequest.put("quantity", quantity);
            tradeRequest.put("action", isBuy ? "buy" : "sell");
            tradeRequest.put("type", "market");
            
            HttpPost request = new HttpPost(BASE_URL + TRADE_ENDPOINT);
            request.setHeader("Authorization", "Bearer " + sessionToken);
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(gson.toJson(tradeRequest)));
            
            String response = EntityUtils.toString(httpClient.execute(request).getEntity());
            JsonObject jsonResponse = gson.fromJson(response, JsonObject.class);
            
            if (jsonResponse.has("success") && jsonResponse.get("success").getAsBoolean()) {
                System.out.println("Successfully executed trade: " + 
                    (isBuy ? "Bought " : "Sold ") + quantity + " shares of " + symbol);
                return true;
            }
            
            System.err.println("Trade failed: " + jsonResponse.get("message").getAsString());
            return false;
            
        } catch (Exception e) {
            System.err.println("Error executing trade: " + e.getMessage());
            return false;
        }
    }
    
    public boolean executeAutomatedTrade(String symbol, double predictedChange, double confidence) {
        try {
            // Get current portfolio balance
            double balance = getPortfolioBalance();
            if (balance <= 0) {
                System.err.println("Insufficient balance or error getting balance");
                return false;
            }
            
            // Enhanced position sizing calculation
            double maxRiskPercent = 0.02; // Maximum 2% risk per trade
            double volatility = Math.abs(predictedChange) * 0.5; // Use half of predicted change as volatility estimate
            double stopLossPercent = Math.max(0.02, volatility * 0.5); // Minimum 2% stop loss
            double takeProfitPercent = Math.max(stopLossPercent * 1.5, Math.abs(predictedChange)); // Minimum 1.5:1 reward:risk
            
            // Calculate maximum position size based on risk
            double maxRiskAmount = balance * maxRiskPercent;
            double positionSize = maxRiskAmount / stopLossPercent;
            
            // Adjust position size based on confidence
            positionSize = positionSize * confidence;
            
            // Get current stock price
            double currentPrice = getCurrentPrice(symbol);
            if (currentPrice <= 0) {
                System.err.println("Error getting current price for " + symbol);
                return false;
            }
            
            // Calculate quantity
            int quantity = (int)(positionSize / currentPrice);
            if (quantity <= 0) {
                System.err.println("Position size too small for " + symbol);
                return false;
            }
            
            // Log trade details
            System.out.println("Trade Details for " + symbol + ":");
            System.out.println("Position Size: $" + String.format("%.2f", positionSize));
            System.out.println("Quantity: " + quantity);
            System.out.println("Stop Loss: " + String.format("%.1f", stopLossPercent * 100) + "%");
            System.out.println("Take Profit: " + String.format("%.1f", takeProfitPercent * 100) + "%");
            System.out.println("Risk/Reward Ratio: " + String.format("%.2f", takeProfitPercent / stopLossPercent));
            
            // Execute trade based on predicted change direction
            return executeTrade(symbol, quantity, predictedChange > 0);
            
        } catch (Exception e) {
            System.err.println("Error in automated trading: " + e.getMessage());
            return false;
        }
    }
    
    private double getCurrentPrice(String symbol) {
        // This would need to be implemented to get the current market price
        // Could use your existing Alpha Vantage integration or another data source
        return -1;
    }
} 