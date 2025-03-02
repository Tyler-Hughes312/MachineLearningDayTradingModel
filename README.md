# Machine Learning Day Trading Model

A sophisticated stock price prediction and automated trading system that combines machine learning with eToro virtual portfolio integration.

## Features

### Stock Price Prediction
- Real-time stock data fetching from Alpha Vantage API
- Machine learning-based price predictions for the next trading day
- Support for 30 major stocks including AAPL, MSFT, GOOGL, AMZN, etc.
- Automatic fallback to mock data generation when API limits are reached
- Comprehensive technical analysis including:
  - Price predictions (open, high, low, close)
  - Volume analysis
  - Market sentiment indicators
  - Trend strength assessment
  - Volatility metrics

### Trading Integration
- eToro virtual portfolio integration
- Manual trading capabilities (buy/sell)
- Automated trading based on ML predictions
- Risk management features:
  - Position sizing recommendations
  - Stop-loss calculations
  - Take-profit targets
  - Risk/reward ratio analysis
  - Maximum 2% risk per trade

### User Interface
- Modern, responsive web interface
- Real-time data visualization
- Top 20 predicted returns dashboard
- Clear market sentiment indicators
- Trading signals with confidence scores
- Portfolio balance tracking

## Setup

### Prerequisites
- Java 11 or higher
- Maven
- eToro virtual trading account
- Alpha Vantage API key

### Project Structure
```
.
├── config.properties           # Configuration file
├── data/                      # Data directory
│   ├── csv/                   # CSV files for stock data
│   └── json/                  # JSON files for API responses
├── src/                       # Source code
└── target/                    # Compiled files
```

### Configuration
1. Create a `config.properties` file in the project root:
```
api.key=YOUR_ALPHA_VANTAGE_API_KEY
etoro.username=YOUR_ETORO_USERNAME
etoro.password=YOUR_ETORO_PASSWORD
```

2. Install dependencies:
```bash
mvn clean install
```

3. Run the application:
```bash
mvn exec:java -Dexec.mainClass="WebVisualization"
```

The server will start on port 8080 (or the next available port up to 8089).

### API Key Setup
1. Sign up for an Alpha Vantage API key at https://www.alphavantage.co/support/#api-key
2. Create a file named `AlphaVantageAPI.txt` in the project root directory
3. Add your API key to this file (single line, no quotes or spaces)
4. This file is ignored by git to keep your API key secure

Alternatively, you can add your API key to `config.properties`:
1. Copy `config.properties.template` to `config.properties`
2. Replace `YOUR_API_KEY_HERE` with your actual API key

**Note:** Never commit files containing your actual API key to version control.

## Usage

1. Access the web interface at `http://localhost:8080`

2. Log in to your eToro virtual portfolio

3. For individual stock analysis:
   - Enter a stock symbol (e.g., AAPL)
   - View comprehensive prediction results
   - Execute manual or automated trades

4. Monitor the Top 20 Predictions dashboard for the best trading opportunities

### Trading Features

#### Manual Trading
- Use the "Buy Stock" and "Sell Stock" buttons for manual trades
- Default position size: 1 share per trade
- Real-time portfolio balance updates

#### Automated Trading
- Click "Auto Trade" for ML-driven trading decisions
- Minimum 80% confidence threshold for automated trades
- Automated position sizing based on:
  - Account balance
  - Stock volatility
  - Prediction confidence
  - Maximum 2% risk per trade

## Technical Details

### Data Sources
- Primary: Alpha Vantage API (real-time market data)
- Backup: Sophisticated mock data generation with realistic price movements
- Data Storage:
  - CSV files in `data/csv/` for historical stock data
  - JSON files in `data/json/` for API responses

### Machine Learning Model
- Features:
  - Historical price data
  - Volume analysis
  - Technical indicators
  - Market trends
- Performance metrics:
  - Correlation coefficient
  - Mean absolute error
  - Root mean squared error
  - Relative absolute error

### Error Handling
- Automatic failover to mock data when API limits are reached
- Comprehensive error reporting
- Rate limit management
- Data validation and sanitization

## Limitations
- Alpha Vantage API has rate limits (calls per minute/day)
- Mock data is used when API limits are reached
- Virtual trading only (no real money transactions)
- Limited to supported stock symbols

## Contributing
Feel free to submit issues and enhancement requests.

## License
This project is licensed under the MIT License - see the LICENSE file for details. 