package com.westart.ai.westart.service.tool;

import com.westart.ai.westart.util.GenerateWeatherJWT;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;

@Service("weatherServiceImpl")
@RequiredArgsConstructor
public class WeatherService{

    private static final Logger LOGGER = LoggerFactory.getLogger(WeatherService.class);
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    /**
     * 获取环境配置
     * @param name
     * @param defaultValue
     * @return
     */
    private String getEnvironmentVariable(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @Tool(value="当用户需要查询某个城市的实时天气时，调用该工具，province表示省份，cityName为城市名，"+
            "如果用户缺少了省份或城市名，则不调用该方法，并提示用户明确具体的省份和城市")
    public String queryWeatherInfo(String province,String cityName) {
        try {
            String locationId = queryCityId(province,cityName);
            String jwt = GenerateWeatherJWT.generateJWT();
            String apiHost = getEnvironmentVariable(
                    "API_HOST", "devapi.qweather.com");

            String url = "https://"+apiHost+ "/v7/weather/now?location=" + locationId;
            LOGGER.info(url);
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + jwt)
                    .header("Accept", "application/json")
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                String body = responseBody == null ? "" : responseBody.string();

                if (!response.isSuccessful()) {
                    throw new IOException("天气接口请求失败，HTTP "
                            + response.code() + ": " + body);
                }
                return body;
            }

        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                 | SignatureException | IOException e) {
            throw new RuntimeException("查询天气信息失败", e);
        }
    }

    /**
     * 根据省份和城市名获取城市ID
     * @param province
     * @param cityName
     * @return
     */
    private String queryCityId(String province, String cityName) {
        try {
            String jwt = GenerateWeatherJWT.generateJWT();
            String apiHost = getEnvironmentVariable(
                    "API_HOST", "devapi.qweather.com");

            HttpUrl url = new HttpUrl.Builder()
                    .scheme("https")
                    .host(apiHost)
                    .addPathSegments("geo/v2/city/lookup")
                    .addQueryParameter("location", cityName)
                    .addQueryParameter("adm", province)
                    .addQueryParameter("range", "cn")
                    .addQueryParameter("number", "1")
                    .addQueryParameter("lang", "zh")
                    .build();

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", "Bearer " + jwt)
                    .header("Accept", "application/json")
                    .build();

            try (Response response = okHttpClient.newCall(request).execute()) {
                ResponseBody responseBody = response.body();
                String body = responseBody == null ? "" : responseBody.string();

                if (!response.isSuccessful()) {
                    throw new IOException("城市查询接口请求失败，HTTP "
                            + response.code() + ": " + body);
                }

                JsonNode root = objectMapper.readTree(body);
                if (!"200".equals(root.path("code").asText())) {
                    throw new IOException("城市查询接口返回异常状态：" + body);
                }

                JsonNode locations = root.path("location");
                if (!locations.isArray() || locations.isEmpty()
                        || locations.get(0).path("id").asText().isBlank()) {
                    throw new IOException("未查询到城市 " + province + " " + cityName);
                }
                return locations.get(0).path("id").asText();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                 | SignatureException | IOException e) {
            throw new RuntimeException("查询城市ID失败", e);
        }
    }
}
