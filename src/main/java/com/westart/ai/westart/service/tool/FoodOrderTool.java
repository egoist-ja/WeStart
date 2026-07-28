package com.westart.ai.westart.service.tool;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 周边餐厅搜索工具。
 *
 * 基于高德地图 POI 周边搜索发现附近餐饮店铺，返回餐厅详细信息
 * 及高德导航链接。用户可通过电话直接联系餐厅点餐，或导航到店就餐。
 *
 * 调用链路：
 * 用户发送"附近有什么好吃的" → AI 先用 gaodeMapTool 将地址转为经纬度
 * → AI 调用 {@link #searchNearbyRestaurants} → 高德周边搜索（types=050000 餐饮）
 * → 解析 POI 列表 → 返回格式化结果（含导航链接和点餐指引）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FoodOrderTool {

    private static final String AMAP_BASE_URL = "restapi.amap.com";
    private static final String AMAP_NAVI_BASE = "https://uri.amap.com/navigation";
    private static final String FOOD_TYPES = "050000";
    private static final int MAX_RESTAURANTS = 8;

    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    /**
     * 获取高德 API Key。
     */
    private String getAmapKey() {
        String apiKey = System.getenv("AMAP_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("环境变量 AMAP_KEY 未设置");
        }
        return apiKey;
    }

    /**
     * 搜索周边餐厅。
     *
     * 基于用户位置和偏好，通过高德 POI 周边搜索发现附近餐饮店铺，
     * 返回包含餐厅详情、导航链接和点餐指引的格式化结果。
     *
     * @param location 中心点坐标，格式"经度,纬度"（必填）
     * @param keywords 菜系或餐厅关键词（选填，如川菜、火锅、快餐、烧烤）
     * @param radius   搜索半径，单位米（选填，默认3000，范围0-50000）
     * @return 格式化的餐厅列表，含名称、地址、评分、人均、电话和导航链接
     */
    @Tool("搜索周边餐厅。" +
          "location=中心点\"经度,纬度\"（必填，需先用高德地理编码将用户地址转为经纬度），" +
          "keywords=菜系或餐厅关键词（选填，如川菜、火锅、快餐、烧烤），" +
          "radius=搜索半径米（选填，默认3000）")
    public String searchNearbyRestaurants(String location, String keywords, String radius) {
        try {
            HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                    .scheme("https")
                    .host(AMAP_BASE_URL)
                    .addPathSegments("v3/place/around")
                    .addQueryParameter("key", getAmapKey())
                    .addQueryParameter("location", location)
                    .addQueryParameter("types", FOOD_TYPES)
                    .addQueryParameter("offset", String.valueOf(MAX_RESTAURANTS))
                    .addQueryParameter("extensions", "all");

            String resolvedRadius = (radius != null && !radius.isBlank()) ? radius : "3000";
            urlBuilder.addQueryParameter("radius", resolvedRadius);

            if (keywords != null && !keywords.isBlank()) {
                urlBuilder.addQueryParameter("keywords", keywords);
            }

            String url = urlBuilder.build().toString();
            log.info("周边餐厅搜索开始，location={}，keywords={}，radius={}",
                    location,
                    keywords == null || keywords.isBlank() ? "未指定" : keywords,
                    resolvedRadius);

            String responseBody = executeAmapRequest(url);
            return formatRestaurantList(responseBody, keywords);

        } catch (IOException e) {
            throw new RuntimeException("周边餐厅搜索失败", e);
        }
    }

    /**
     * 解析高德 POI 响应，格式化为带操作指引的餐厅列表。
     */
    private String formatRestaurantList(String responseBody, String keywords) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            if (!"1".equals(root.path("status").asText())) {
                String info = root.path("info").asText("未知错误");
                log.error("高德周边搜索返回异常状态：{}", info);
                return "周边餐厅搜索失败：" + info;
            }

            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return "附近暂未搜索到餐厅，建议扩大搜索范围或换个关键词试试。";
            }

            StringBuilder output = new StringBuilder();
            output.append("为你找到以下周边餐厅：\n\n");

            int count = 0;
            for (JsonNode poi : pois) {
                if (count >= MAX_RESTAURANTS) {
                    break;
                }

                String name = poi.path("name").asText("");
                String address = poi.path("address").asText("");
                String distance = poi.path("distance").asText("");
                String tel = poi.path("tel").asText("");

                JsonNode bizExt = poi.path("biz_ext");
                String rating = bizExt.path("rating").asText("");
                String cost = bizExt.path("cost").asText("");

                // 营业时间（高德可能返回）
                String businessArea = poi.path("business_area").asText("");
                String opentime = bizExt.path("opentime").asText("");

                if (name.isBlank()) {
                    continue;
                }
                count++;

                // 导航链接
                String lngLat = poi.path("location").asText("");
                String naviLink = lngLat.isBlank() ? ""
                        : AMAP_NAVI_BASE + "?to=" + lngLat + ","
                                + URLEncoder.encode(name, StandardCharsets.UTF_8);

                output.append(count).append(". ").append(name);
                if (!rating.isBlank()) {
                    output.append("  ⭐").append(rating);
                }
                if (!cost.isBlank()) {
                    output.append("  人均¥").append(cost);
                }
                output.append("\n");

                if (!address.isBlank() || !distance.isBlank()) {
                    output.append("   📍");
                    if (!distance.isBlank()) {
                        output.append(formatDistance(distance));
                    }
                    if (!address.isBlank()) {
                        if (!distance.isBlank()) {
                            output.append(" | ");
                        }
                        output.append(address);
                    }
                    output.append("\n");
                }

                if (!businessArea.isBlank()) {
                    output.append("   🏷️").append(businessArea).append("\n");
                }
                if (!opentime.isBlank()) {
                    output.append("   🕐").append(opentime).append("\n");
                }
                if (!tel.isBlank()) {
                    output.append("   📞").append(tel).append("（可直接拨打点餐）\n");
                }
                if (!naviLink.isBlank()) {
                    output.append("   🗺️导航：").append(naviLink).append("\n");
                }
                output.append("\n");
            }

            // 点餐操作指引
            output.append("----\n");
            output.append("📌 点餐操作指引：\n");
            output.append("1. 外卖点餐：打开美团/饿了么APP → 搜索餐厅名称 → 选择菜品下单\n");
            output.append("2. 电话点餐：拨打上方餐厅电话，直接向商家点餐\n");
            output.append("3. 到店就餐：点击导航链接即可跳转高德地图导航\n");
            output.append("4. 建议点餐前先电话确认营业时间和配送范围哦");

            return output.toString();

        } catch (Exception e) {
            log.error("解析高德周边搜索响应失败", e);
            throw new RuntimeException("解析餐厅搜索结果失败", e);
        }
    }

    /**
     * 格式化距离显示。
     */
    private String formatDistance(String distanceMeters) {
        try {
            int meters = Integer.parseInt(distanceMeters);
            if (meters >= 1000) {
                return String.format("%.1f公里", meters / 1000.0);
            }
            return meters + "米";
        } catch (NumberFormatException e) {
            return distanceMeters + "米";
        }
    }

    /**
     * 执行高德 HTTP GET 请求。
     */
    private String executeAmapRequest(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : responseBody.string();

            if (!response.isSuccessful()) {
                log.error("高德地图接口请求失败，HTTP {}: {}", response.code(), body);
                throw new IOException("高德地图接口请求失败，HTTP "
                        + response.code() + ": " + body);
            }
            log.info("高德地图接口请求成功，响应长度={}", body.length());
            return body;
        }
    }

}
