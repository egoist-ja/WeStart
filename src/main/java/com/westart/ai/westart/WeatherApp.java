package com.westart.ai.westart;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.*;

public class WeatherApp {

    private static final Logger logger = Logger.getLogger(WeatherApp.class.getName());
    private static final String API_URL = "https://api.seniverse.com/v3/weather/now.json";

    // 配置你的API Key和城市
    private static final String API_KEY = "SKNzFggz61SEUJgxX";
    private static final String LOCATION = "beijing";

    public static void main(String[] args) {
        setupLogger();
        logger.info("========== 天气查询程序启动 ==========");

        try {
            String weatherData = fetchWeather(LOCATION);
            logger.info("天气数据获取成功");
            logger.info("原始响应: " + weatherData);

            // 解析并打印关键信息
            parseAndPrintWeather(weatherData);

        } catch (Exception e) {
            logger.severe("获取天气数据失败: " + e.getMessage());
            e.printStackTrace();
        }

        logger.info("========== 程序执行完成 ==========");
    }

    private static void setupLogger() {
        try {
            // 创建文件处理器
            FileHandler fileHandler = new FileHandler("weather.log", true);
            fileHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(fileHandler);

            // 控制台处理器
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setFormatter(new SimpleFormatter());
            logger.addHandler(consoleHandler);

            logger.setLevel(Level.ALL);

        } catch (Exception e) {
            System.err.println("日志初始化失败: " + e.getMessage());
        }
    }

    private static String fetchWeather(String location) throws Exception {
        String encodedLocation = URLEncoder.encode(location, StandardCharsets.UTF_8.toString());
        String urlString = API_URL + "?key=" + API_KEY + "&location=" + encodedLocation + "&language=zh-Hans&unit=c";

        logger.info("请求URL: " + urlString);
        logger.info("请求时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Accept", "application/json");

            int responseCode = connection.getResponseCode();
            logger.info("HTTP响应码: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)
                );

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                logger.info("响应数据长度: " + response.length() + " 字符");
                return response.toString();

            } else {
                logger.warning("请求失败，响应码: " + responseCode);
                throw new RuntimeException("API请求失败，响应码: " + responseCode);
            }

        } finally {
            connection.disconnect();
            logger.info("HTTP连接已关闭");
        }
    }

    private static void parseAndPrintWeather(String jsonData) {
        logger.info("开始解析天气数据");

        // 简单字符串解析（实际项目建议使用JSON库如Jackson/Gson）
        try {
            // 提取城市名
            String city = extractJsonValue(jsonData, "name");
            logger.info("城市: " + city);

            // 提取天气现象
            String text = extractJsonValue(jsonData, "text");
            logger.info("天气: " + text);

            // 提取温度
            String temperature = extractJsonValue(jsonData, "temperature");
            logger.info("温度: " + temperature + "°C");

            // 提取体感温度
            String feelsLike = extractJsonValue(jsonData, "feels_like");
            logger.info("体感温度: " + feelsLike + "°C");

            // 提取更新时间
            String lastUpdate = extractJsonValue(jsonData, "last_update");
            logger.info("数据更新时间: " + lastUpdate);

            // 打印格式化输出
            System.out.println("\n========== 天气信息 ==========");
            System.out.println("城市: " + city);
            System.out.println("天气: " + text);
            System.out.println("温度: " + temperature + "°C");
            System.out.println("体感温度: " + feelsLike + "°C");
            System.out.println("更新时间: " + lastUpdate);
            System.out.println("==============================\n");

        } catch (Exception e) {
            logger.severe("解析天气数据失败: " + e.getMessage());
        }
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return "未知";

        startIndex += searchKey.length();
        int endIndex = json.indexOf(",", startIndex);
        if (endIndex == -1) endIndex = json.indexOf("}", startIndex);

        String value = json.substring(startIndex, endIndex).trim();
        return value.replace("\"", "");
    }
}
