package com.westart.ai.westart.service.tool;

import com.westart.ai.westart.util.GenerateWeatherJWT;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherTool{

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

    @Tool(value = "查询中国指定城市的实时天气信息。仅用于当前天气查询，不用于天气预报或历史天气。"
            + "province为省级行政区名称，cityName为城市名称，二者均为必填参数；缺少时先询问用户。")
    public String queryWeatherInfo(String province, String cityName)
            throws NoSuchAlgorithmException, InvalidKeySpecException,
            InvalidKeyException, SignatureException, IOException {
        log.info("调用天气查询工具，province={}，cityName={}", province, cityName);
        String locationId = queryCityId(province, cityName);
        String jwt = GenerateWeatherJWT.generateJWT();
        String apiHost = getEnvironmentVariable("API_HOST", "devapi.qweather.com");

        String url = "https://" + apiHost + "/v7/weather/now?location=" + locationId;
        log.info(url);

        IOException lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return executeWeatherRequest(url, province, cityName);
            } catch (IOException e) {
                lastException = e;
                if (attempt < 3) {
                    long delayMs = attempt * 1000L + (long) (Math.random() * 500);
                    log.warn("天气查询第{}次尝试失败，{}ms后重试：{}", attempt, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        log.error("天气查询最终失败，province={}，cityName={}", province, cityName, lastException);
        throw lastException;
    }

    private String executeWeatherRequest(String url, String province, String cityName)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException,
            InvalidKeyException, SignatureException {
        String jwt = GenerateWeatherJWT.generateJWT();
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
                log.error("天气接口请求失败，province={}，cityName={}，HTTP {}：{}",
                        province, cityName, response.code(), body);
                throw new IOException("天气接口请求失败，HTTP "
                        + response.code() + ": " + body);
            }
            log.info("天气查询成功，province={}，cityName={}，响应长度={} chars",
                    province, cityName, body.length());
            return body;
        }
    }

    /**
     * 根据省份和城市名获取城市ID
     * @param province
     * @param cityName
     * @return
     */
    private String queryCityId(String province, String cityName)
            throws NoSuchAlgorithmException, InvalidKeySpecException,
            InvalidKeyException, SignatureException, IOException {
        String jwt = GenerateWeatherJWT.generateJWT();
        String apiHost = getEnvironmentVariable("API_HOST", "devapi.qweather.com");

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
    }
}
