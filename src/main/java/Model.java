import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.functions.MultilayerPerceptron;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.FastVector;
import weka.core.Instance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.NumericToNominal;
import weka.filters.unsupervised.attribute.RemoveUseless;
import weka.filters.unsupervised.instance.RemoveWithValues;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.text.DecimalFormat;
import java.util.Random;

public class Model {
    private Instances trainingData;
    private Classifier classifier;
    private ArrayList<Attribute> attributes;
    
    // Technical indicators
    private static final int SMA_PERIOD = 20;  // 20-day Simple Moving Average
    private static final int RSI_PERIOD = 14;  // 14-day Relative Strength Index
    private static final int MACD_FAST = 12;   // MACD fast period
    private static final int MACD_SLOW = 26;   // MACD slow period
    private static final int MACD_SIGNAL = 9;  // MACD signal period

    private double lastCorrelationCoefficient;

    public Model() {
        initializeAttributes();
        this.lastCorrelationCoefficient = 0.0;
    }

    private void initializeAttributes() {
        attributes = new ArrayList<>();
        
        // Add attributes for our features
        attributes.add(new Attribute("Open"));
        attributes.add(new Attribute("High"));
        attributes.add(new Attribute("Low"));
        attributes.add(new Attribute("Close"));
        attributes.add(new Attribute("Volume"));
        attributes.add(new Attribute("SMA20"));    // 20-day Simple Moving Average
        attributes.add(new Attribute("RSI"));      // Relative Strength Index
        attributes.add(new Attribute("MACD"));     // Moving Average Convergence Divergence
        attributes.add(new Attribute("Signal"));   // MACD Signal line
        attributes.add(new Attribute("Target"));   // Next day's closing price (what we're predicting)
    }

    public void prepareData(List<StockDataManager.StockEntry> stockData) {
        // Create empty dataset with our attributes
        trainingData = new Instances("StockPrediction", attributes, 0);
        trainingData.setClassIndex(trainingData.numAttributes() - 1);  // Set target attribute

        // Ensure we have enough data
        if (stockData == null || stockData.size() < MACD_SLOW + MACD_SIGNAL) {
            System.err.println("Not enough data points for training. Need at least " + (MACD_SLOW + MACD_SIGNAL) + " points.");
            return;
        }

        // Calculate technical indicators and add instances
        List<Double> closePrices = new ArrayList<>();
        List<Double> volumes = new ArrayList<>();
        
        for (StockDataManager.StockEntry entry : stockData) {
            closePrices.add(entry.close);
            volumes.add(entry.volume);
        }

        // We need enough data for all our indicators
        int requiredDataPoints = Math.max(Math.max(MACD_SLOW + MACD_SIGNAL, SMA_PERIOD), RSI_PERIOD);
        
        // Calculate indicators for each day
        for (int i = requiredDataPoints; i < stockData.size() - 1; i++) {
            try {
                StockDataManager.StockEntry entry = stockData.get(i);
                
                // Calculate technical indicators
                double sma = calculateSMA(closePrices, i, SMA_PERIOD);
                double rsi = calculateRSI(closePrices, i, RSI_PERIOD);
                double[] macdValues = calculateMACD(closePrices, i);
                
                // Create instance with calculated features
                double[] values = new double[trainingData.numAttributes()];
                values[0] = entry.open;
                values[1] = entry.high;
                values[2] = entry.low;
                values[3] = entry.close;
                values[4] = entry.volume;
                values[5] = sma;
                values[6] = rsi;
                values[7] = macdValues[0]; // MACD line
                values[8] = macdValues[1]; // Signal line
                values[9] = stockData.get(i + 1).close; // Next day's close price (target)

                trainingData.add(new DenseInstance(1.0, values));
            } catch (Exception e) {
                System.err.println("Error processing data point at index " + i + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        System.out.println("Prepared " + trainingData.numInstances() + " instances for training");
    }

    private double calculateSMA(List<Double> prices, int currentIndex, int period) {
        // Ensure we have enough data points
        if (currentIndex < period - 1 || currentIndex >= prices.size()) {
            return prices.get(prices.size() - 1); // Return last available price if not enough data
        }
        
        double sum = 0;
        for (int i = currentIndex - period + 1; i <= currentIndex; i++) {
            if (i >= 0 && i < prices.size()) {
                sum += prices.get(i);
            }
        }
        return sum / period;
    }

    private double calculateRSI(List<Double> prices, int currentIndex, int period) {
        // Ensure we have enough data points
        if (currentIndex < period || currentIndex >= prices.size()) {
            return 50.0; // Return neutral RSI if not enough data
        }
        
        double gains = 0;
        double losses = 0;
        
        for (int i = currentIndex - period + 1; i <= currentIndex; i++) {
            if (i > 0 && i < prices.size()) { // Check if we have a previous price to compare with
                double difference = prices.get(i) - prices.get(i - 1);
                if (difference >= 0) {
                    gains += difference;
                } else {
                    losses -= difference;
                }
            }
        }
        
        if (losses == 0) return 100;
        
        double relativeStrength = gains / losses;
        return 100 - (100 / (1 + relativeStrength));
    }

    private double[] calculateMACD(List<Double> prices, int currentIndex) {
        // Ensure we have enough data points for both fast and slow EMAs
        if (currentIndex < MACD_SLOW || currentIndex >= prices.size()) {
            return new double[]{0.0, 0.0}; // Return neutral values if not enough data
        }
        
        double fastEMA = calculateEMA(prices, currentIndex, MACD_FAST);
        double slowEMA = calculateEMA(prices, currentIndex, MACD_SLOW);
        double macd = fastEMA - slowEMA;
        
        // Calculate signal line (9-day EMA of MACD)
        List<Double> macdValues = new ArrayList<>();
        for (int i = Math.max(0, currentIndex - MACD_SIGNAL + 1); i <= currentIndex; i++) {
            if (i >= MACD_FAST && i >= MACD_SLOW && i < prices.size()) {
                double fast = calculateEMA(prices, i, MACD_FAST);
                double slow = calculateEMA(prices, i, MACD_SLOW);
                macdValues.add(fast - slow);
            }
        }
        
        double signal = calculateEMAFromList(macdValues, MACD_SIGNAL);
        
        return new double[]{macd, signal};
    }

    private double calculateEMA(List<Double> prices, int currentIndex, int period) {
        // Ensure we have enough data points
        if (currentIndex < period - 1) {
            return prices.get(currentIndex); // Return current price if not enough data
        }
        
        // Calculate SMA first
        double sma = 0;
        for (int i = currentIndex - period + 1; i <= currentIndex; i++) {
            sma += prices.get(i);
        }
        sma /= period;
        
        double multiplier = 2.0 / (period + 1);
        double ema = sma;
        
        // Calculate EMA
        for (int i = currentIndex - period + 2; i <= currentIndex; i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }
        
        return ema;
    }

    private double calculateEMAFromList(List<Double> values, int period) {
        if (values.isEmpty()) {
            return 0.0; // Return neutral value if no data
        }
        
        // Calculate SMA first
        double sma = 0;
        for (int i = 0; i < period && i < values.size(); i++) {
            sma += values.get(i);
        }
        sma /= Math.min(period, values.size());
        
        double multiplier = 2.0 / (period + 1);
        double ema = sma;
        
        // Calculate EMA
        for (int i = period; i < values.size(); i++) {
            ema = (values.get(i) - ema) * multiplier + ema;
        }
        
        return ema;
    }

    public void trainModel() {
        try {
            // Create and configure the Random Forest classifier
            classifier = new RandomForest();
            ((RandomForest) classifier).setNumIterations(100);
            
            // Train the model
            classifier.buildClassifier(trainingData);
            
            // Evaluate the model using cross-validation
            Evaluation eval = new Evaluation(trainingData);
            eval.crossValidateModel(classifier, trainingData, 10, new Random(1));
            
            // Store the correlation coefficient
            this.lastCorrelationCoefficient = eval.correlationCoefficient();
            
            // Print evaluation metrics
            System.out.println("\nModel Evaluation Results:");
            System.out.println("Correlation coefficient: " + eval.correlationCoefficient());
            System.out.println("Mean absolute error: " + eval.meanAbsoluteError());
            System.out.println("Root mean squared error: " + eval.rootMeanSquaredError());
            System.out.println("Relative absolute error: " + eval.relativeAbsoluteError() + "%");
            System.out.println("Root relative squared error: " + eval.rootRelativeSquaredError() + "%");
            
        } catch (Exception e) {
            System.err.println("Error training model: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public double getLastEvaluationCorrelation() {
        return this.lastCorrelationCoefficient;
    }

    public double predictNextDayPrice(StockDataManager.StockEntry lastEntry, 
                                    List<StockDataManager.StockEntry> historicalData) {
        try {
            // Ensure we have enough historical data
            if (historicalData == null || historicalData.size() < MACD_SLOW) {
                return lastEntry.close; // Return current price if not enough data
            }

            // Prepare the instance for prediction
            double[] values = new double[trainingData.numAttributes()];
            values[0] = lastEntry.open;
            values[1] = lastEntry.high;
            values[2] = lastEntry.low;
            values[3] = lastEntry.close;
            values[4] = lastEntry.volume;
            
            // Calculate technical indicators for the last entry
            List<Double> closePrices = historicalData.stream()
                .map(entry -> entry.close)
                .collect(java.util.stream.Collectors.toList());
                
            int lastIndex = closePrices.size() - 1;
            values[5] = calculateSMA(closePrices, lastIndex, SMA_PERIOD);
            values[6] = calculateRSI(closePrices, lastIndex, RSI_PERIOD);
            
            double[] macdValues = calculateMACD(closePrices, lastIndex);
            values[7] = macdValues[0];
            values[8] = macdValues[1];
            
            // Create and prepare the instance
            Instance predictionInstance = new DenseInstance(1.0, values);
            predictionInstance.setDataset(trainingData);
            
            // Make base prediction
            double rawPrediction = classifier.classifyInstance(predictionInstance);
            
            // Check if market is closed
            java.time.LocalTime currentTime = java.time.LocalTime.now();
            java.time.LocalTime marketOpen = java.time.LocalTime.of(9, 30);  // Market opens at 9:30 AM
            java.time.LocalTime marketClose = java.time.LocalTime.of(16, 0); // Market closes at 4:00 PM
            boolean isMarketClosed = currentTime.isAfter(marketClose) || currentTime.isBefore(marketOpen);
            
            if (isMarketClosed) {
                // When market is closed, predict next day's opening price
                // Calculate overnight sentiment based on technical indicators
                double sentimentAdjustment = 0.0;
                
                // RSI-based adjustment (stronger influence)
                if (values[6] > 70) {
                    sentimentAdjustment -= 0.02; // Overbought, expect decline
                } else if (values[6] < 30) {
                    sentimentAdjustment += 0.02; // Oversold, expect rise
                }
                
                // MACD-based adjustment (stronger influence)
                if (values[7] > values[8]) {
                    sentimentAdjustment += 0.015; // Bullish MACD crossover
                } else if (values[7] < values[8]) {
                    sentimentAdjustment -= 0.015; // Bearish MACD crossover
                }
                
                // SMA trend adjustment (stronger influence)
                if (values[3] > values[5]) {
                    sentimentAdjustment += 0.01; // Price above SMA, bullish trend
                } else if (values[3] < values[5]) {
                    sentimentAdjustment -= 0.01; // Price below SMA, bearish trend
                }
                
                // Calculate overnight volatility (using previous day's range)
                double previousDayRange = lastEntry.high - lastEntry.low;
                double volatilityFactor = previousDayRange / lastEntry.close;
                
                // Apply sentiment and volatility adjustments to the prediction
                double adjustedPrediction = rawPrediction * (1 + sentimentAdjustment);
                
                // Calculate the expected overnight movement
                double expectedMovement = (adjustedPrediction - lastEntry.close) * (1 + volatilityFactor);
                
                // Calculate final prediction for next day's open
                double finalPrediction = lastEntry.close + expectedMovement;
                
                // Ensure prediction is reasonable but allow for larger movements
                double maxDeviation = 0.05; // 5% maximum deviation for overnight
                double currentPrice = lastEntry.close;
                double minPrediction = currentPrice * (1 - maxDeviation);
                double maxPrediction = currentPrice * (1 + maxDeviation);
                
                return Math.min(Math.max(finalPrediction, minPrediction), maxPrediction);
            } else {
                // Market is open - predict end of day price
                // Calculate remaining trading time as a percentage of the trading day
                double tradingDayMinutes = 390.0; // 6.5 hours * 60 minutes
                double remainingMinutes = 0.0;
                
                if (currentTime.isBefore(marketOpen)) {
                    remainingMinutes = tradingDayMinutes; // Full day if before market open
                } else {
                    remainingMinutes = java.time.Duration.between(currentTime, marketClose).toMinutes();
                }
                
                double timeFactor = remainingMinutes / tradingDayMinutes;
                
                // Apply market sentiment adjustment based on technical indicators
                double sentimentAdjustment = 0.0;
                
                // RSI-based adjustment (stronger influence)
                if (values[6] > 70) {
                    sentimentAdjustment -= 0.03; // Overbought, expect decline
                } else if (values[6] < 30) {
                    sentimentAdjustment += 0.03; // Oversold, expect rise
                }
                
                // MACD-based adjustment (stronger influence)
                if (values[7] > values[8]) {
                    sentimentAdjustment += 0.02; // Bullish MACD crossover
                } else if (values[7] < values[8]) {
                    sentimentAdjustment -= 0.02; // Bearish MACD crossover
                }
                
                // SMA trend adjustment (stronger influence)
                if (values[3] > values[5]) {
                    sentimentAdjustment += 0.015; // Price above SMA, bullish trend
                } else if (values[3] < values[5]) {
                    sentimentAdjustment -= 0.015; // Price below SMA, bearish trend
                }
                
                // Calculate intraday volatility
                double intradayRange = lastEntry.high - lastEntry.low;
                double volatilityFactor = intradayRange / lastEntry.close;
                
                // Apply time-based and sentiment adjustments to the prediction
                double adjustedPrediction = rawPrediction * (1 + sentimentAdjustment);
                
                // Calculate the expected movement based on remaining time and volatility
                double expectedMovement = (adjustedPrediction - lastEntry.close) * timeFactor;
                
                // Apply volatility factor to the movement (stronger influence)
                expectedMovement *= (1 + volatilityFactor * 2);
                
                // Calculate final prediction for end of day
                double finalPrediction = lastEntry.close + expectedMovement;
                
                // Ensure prediction is reasonable but allow for larger movements
                double maxDeviation = 0.15; // 15% maximum deviation
                double currentPrice = lastEntry.close;
                double minPrediction = currentPrice * (1 - maxDeviation);
                double maxPrediction = currentPrice * (1 + maxDeviation);
                
                return Math.min(Math.max(finalPrediction, minPrediction), maxPrediction);
            }
            
        } catch (Exception e) {
            System.err.println("Error making prediction: " + e.getMessage());
            e.printStackTrace();
            return lastEntry.close; // Return current price as fallback
        }
    }
} 