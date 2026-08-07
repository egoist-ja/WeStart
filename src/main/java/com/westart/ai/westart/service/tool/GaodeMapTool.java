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

import java.io.IOException;

/**
 * 高德地图工具服务。
 *
 * 提供地理编码、逆地理编码、POI 搜索、周边搜索和 IP 定位能力。
 * 所有方法均通过 {@code System.getenv("AMAP_KEY")} 读取高德 Web 服务 API Key。
 *
 * 工具协作关系
 * 精确位置场景的标准调用链路：
 * 用户口述地址 → {@link #geocode} 将文字地址转为精确经纬度
 * 拿到坐标后 → {@link #searchAround} 搜索周边 POI
 * 或拿到坐标后 → {@link #regeo} 反查该坐标的详细地址描述
 *
 * 模糊城市搜索场景：
 * 用户指定城市 + 关键词 → 直接 {@link #searchPOI}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GaodeMapTool {

    private static final String AMAP_BASE_URL = "restapi.amap.com";
    private final OkHttpClient okHttpClient;

    /**
     * 获取高德 API Key，从环境变量 {@code AMAP_KEY} 中读取。
     *
     * @return 高德 Web 服务 API Key
     * @throws IllegalStateException 环境变量未设置时抛出
     */
    private String getApiKey() {
        String apiKey = System.getenv("AMAP_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("环境变量 AMAP_KEY 未设置");
        }
        return apiKey;
    }

    //地理编码
    @Tool("将结构化文字地址解析为精确经纬度坐标，用于定位地点并为周边搜索、距离计算和路线规划提供坐标。"
            + "address为完整地址，必填；city为城市名称、citycode或adcode，选填，用于限定搜索范围。")
    public String geocode(String address, String city) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/geocode/geo")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("address", address);

        if (city != null && !city.isBlank()) {
            urlBuilder.addQueryParameter("city", city);
        }

        String url = urlBuilder.build().toString();
        log.info("高德地理编码，address={}，city={}",
                address,
                city == null || city.isBlank() ? "未指定" : city);

        return executeRequest(url);
    }

    //逆地理编码
    @Tool("将经纬度坐标解析为详细文字地址和周边位置描述。"
            + "location为\"经度,纬度\"格式的坐标，必填，不接受文字地址。")
    public String regeo(String location) throws IOException {
        String url = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/geocode/regeo")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("location", location)
                .addQueryParameter("extensions", "all")
                .build()
                .toString();

        log.info("高德逆地理编码，location={}", location);

        return executeRequest(url);
    }

    //POI关键字搜索
    @Tool("按名称、关键词或类别搜索景点、酒店、餐厅、商场、车站等兴趣点，返回地点信息和坐标。"
            + "适用于城市范围内查找地点，不用于以某个坐标为中心的附近搜索。"
            + "keywords为搜索词，必填；city为城市限定，选填；types为POI分类编码，选填。")
    public String searchPOI(String keywords, String city, String types) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/place/text")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("keywords", keywords)
                .addQueryParameter("offset", "20")
                .addQueryParameter("extensions", "all");

        if (city != null && !city.isBlank()) {
            urlBuilder.addQueryParameter("city", city);
        }
        if (types != null && !types.isBlank()) {
            urlBuilder.addQueryParameter("types", types);
        }

        String url = urlBuilder.build().toString();
        log.info("高德POI关键字搜索，keywords={}，city={}，types={}",
                keywords,
                city == null || city.isBlank() ? "全国" : city,
                types == null || types.isBlank() ? "未指定" : types);

        return executeRequest(url);
    }

    //POI周边搜索
    @Tool("根据中心点经纬度搜索指定半径内的景点、酒店、餐厅、商店等周边兴趣点。"
            + "本工具只接受坐标；用户只提供文字地址时，必须先调用地址转坐标工具。"
            + "location格式为\"经度,纬度\"，必填；keywords为搜索词，选填；"
            + "radius为搜索半径米，选填，默认5000，范围0至50000。")
    public String searchAround(String location, String keywords, String radius) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/place/around")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("location", location)
                .addQueryParameter("offset", "20")
                .addQueryParameter("extensions", "all");

        if (keywords != null && !keywords.isBlank()) {
            urlBuilder.addQueryParameter("keywords", keywords);
        }
        if (radius != null && !radius.isBlank()) {
            urlBuilder.addQueryParameter("radius", radius);
        }

        String url = urlBuilder.build().toString();
        log.info("高德POI周边搜索，location={}，keywords={}，radius={}",
                location,
                keywords == null || keywords.isBlank() ? "未指定" : keywords,
                radius == null || radius.isBlank() ? "默认5000" : radius);

        return executeRequest(url);
    }

    //IP定位
    @Tool("根据中国大陆IPv4地址查询其所属省份和城市，仅提供城市级定位。"
            + "ip为IPv4地址；不传时定位的是服务器公网IP，不代表用户位置，"
            + "因此只有用户明确提供IP地址时才可用于用户定位。")
    public String locateIP(String ip) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/ip")
                .addQueryParameter("key", getApiKey());

        if (ip != null && !ip.isBlank()) {
            urlBuilder.addQueryParameter("ip", ip);
        }

        String url = urlBuilder.build().toString();
        log.info("高德IP定位，ip={}", ip == null || ip.isBlank() ? "自动获取（服务器IP）" : ip);

        return executeRequest(url);
    }

    //驾车路线规划
    @Tool("规划两个经纬度坐标之间的驾车路线，可设置避收费、最短路线、避高速和途经点。"
            + "origin和destination分别为起终点\"经度,纬度\"，必填；文字地址必须先转换为坐标。"
            + "strategy和waypoints为选填参数。")
    public String driving(String origin, String destination, String strategy, String waypoints)
            throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/direction/driving")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .addQueryParameter("extensions", "all");

        if (strategy != null && !strategy.isBlank()) {
            urlBuilder.addQueryParameter("strategy", strategy);
        }
        if (waypoints != null && !waypoints.isBlank()) {
            urlBuilder.addQueryParameter("waypoints", waypoints);
        }

        String url = urlBuilder.build().toString();
        log.info("高德驾车路线规划，origin={}，destination={}，strategy={}，waypoints={}",
                origin, destination,
                strategy == null || strategy.isBlank() ? "默认(速度优先)" : strategy,
                waypoints == null || waypoints.isBlank() ? "无途经点" : waypoints);

        return executeRequest(url);
    }

    //公交路线规划
    @Tool("规划两个经纬度坐标之间的公交和地铁出行路线，可选择最快、最少换乘、最少步行或不乘地铁。"
            + "origin和destination分别为起终点坐标，city为所在城市，均为必填；"
            + "文字地址必须先转换为坐标。strategy为选填参数。")
    public String transit(String origin, String destination, String city, String strategy)
            throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/direction/transit/integrated")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .addQueryParameter("city", city)
                .addQueryParameter("extensions", "all");

        if (strategy != null && !strategy.isBlank()) {
            urlBuilder.addQueryParameter("strategy", strategy);
        }

        String url = urlBuilder.build().toString();
        log.info("高德公交路线规划，origin={}，destination={}，city={}，strategy={}",
                origin, destination, city,
                strategy == null || strategy.isBlank() ? "默认(最快捷)" : strategy);

        return executeRequest(url);
    }

    //步行路线规划
    @Tool("规划两个经纬度坐标之间的步行路线。origin和destination分别为起终点\"经度,纬度\"，"
            + "均为必填；文字地址必须先转换为坐标。")
    public String walking(String origin, String destination) throws IOException {
        String url = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/direction/walking")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .build()
                .toString();

        log.info("高德步行路线规划，origin={}，destination={}", origin, destination);

        return executeRequest(url);
    }

    //骑行路线规划
    @Tool("规划两个经纬度坐标之间的骑行路线。origin和destination分别为起终点\"经度,纬度\"，"
            + "均为必填；文字地址必须先转换为坐标。")
    public String bicycling(String origin, String destination) throws IOException {
        String url = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/direction/bicycling")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("origin", origin)
                .addQueryParameter("destination", destination)
                .build()
                .toString();

        log.info("高德骑行路线规划，origin={}，destination={}", origin, destination);

        return executeRequest(url);
    }

    //距离测量
    @Tool("计算一个或多个起点到同一终点的直线、驾车或步行距离。"
            + "origins为起点坐标，destination为终点坐标，均为必填；文字地址必须先转换为坐标。"
            + "type为距离类型，0直线、1驾车、3步行，选填，默认0。")
    public String distance(String origins, String destination, String type) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/distance")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("origins", origins)
                .addQueryParameter("destination", destination);

        if (type != null && !type.isBlank()) {
            urlBuilder.addQueryParameter("type", type);
        }

        String url = urlBuilder.build().toString();
        log.info("高德距离测量，origins={}，destination={}，type={}",
                origins, destination,
                type == null || type.isBlank() ? "默认(直线距离)" : type);

        return executeRequest(url);
    }

    //行政区域查询
    @Tool("查询中国行政区域的区划信息及下级省、市、区县结构。"
            + "keywords为行政区域名称，必填；subdistrict为返回下级区域的层级，选填，范围0至3。")
    public String district(String keywords, String subdistrict) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/config/district")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("keywords", keywords)
                .addQueryParameter("extensions", "all");

        if (subdistrict != null && !subdistrict.isBlank()) {
            urlBuilder.addQueryParameter("subdistrict", subdistrict);
        }

        String url = urlBuilder.build().toString();
        log.info("高德行政区域查询，keywords={}，subdistrict={}",
                keywords,
                subdistrict == null || subdistrict.isBlank() ? "默认(0)" : subdistrict);

        return executeRequest(url);
    }

    //输入提示
    @Tool("根据不完整的地点或地址关键词返回候选地址列表，用于地址补全和消歧，不返回经纬度。"
            + "keywords为不完整地址或地点名称，必填；city为城市限定，选填。"
            + "需要精确坐标时，应在用户确认候选地址后调用地址转坐标工具。")
    public String inputTips(String keywords, String city) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/assistant/inputtips")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("keywords", keywords);

        if (city != null && !city.isBlank()) {
            urlBuilder.addQueryParameter("city", city);
        }

        String url = urlBuilder.build().toString();
        log.info("高德输入提示，keywords={}，city={}",
                keywords,
                city == null || city.isBlank() ? "未指定" : city);

        return executeRequest(url);
    }

    //静态地图
    @Tool("根据中心点坐标生成可访问的静态地图图片链接，并可设置缩放、图片尺寸和地图标注点。"
            + "location为中心点\"经度,纬度\"，必填；zoom、size和markers为选填参数。"
            + "本工具只生成地图图片，不执行地点搜索或路线规划。")
    public String staticMap(String location, String zoom, String size, String markers) {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/staticmap")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("location", location);

        String zoomValue = (zoom != null && !zoom.isBlank()) ? zoom : "14";
        urlBuilder.addQueryParameter("zoom", zoomValue);

        String sizeValue = (size != null && !size.isBlank()) ? size : "400*300";
        urlBuilder.addQueryParameter("size", sizeValue);

        if (markers != null && !markers.isBlank()) {
            String markerParam = "mid,0xFF0000,A:" + markers.replace(";", ";");
            urlBuilder.addQueryParameter("markers", markerParam);
        }

        String mapUrl = urlBuilder.build().toString();
        log.info("高德静态地图生成，location={}，zoom={}，size={}，markers={}",
                location, zoomValue, sizeValue,
                markers == null || markers.isBlank() ? "无标注" : markers);

        return "地图已生成，点击链接查看：\n"
                + mapUrl + "\n\n"
                + "中心坐标：" + location + "\n"
                + "缩放级别：" + zoomValue + "\n"
                + "图片尺寸：" + sizeValue
                + (markers != null && !markers.isBlank()
                    ? "\n标注点：" + markers : "");
    }

    //坐标转换
    @Tool("将GPS、百度或Mapbar坐标转换为高德坐标，用于统一不同来源的经纬度坐标系。"
            + "locations为一个或多个坐标，格式\"lng,lat|lng,lat\"，必填；"
            + "coordsys为源坐标系gps、baidu或mapbar，选填，默认gps。")
    public String coordinateConvert(String locations, String coordsys) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/assistant/coordinate/convert")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("locations", locations);

        if (coordsys != null && !coordsys.isBlank()) {
            urlBuilder.addQueryParameter("coordsys", coordsys);
        }

        String url = urlBuilder.build().toString();
        log.info("高德坐标转换，locations={}，coordsys={}",
                locations,
                coordsys == null || coordsys.isBlank() ? "默认(gps)" : coordsys);

        return executeRequest(url);
    }

    /**
     * 执行 HTTP GET 请求并返回响应体字符串。
     *
     * @param url 完整请求 URL
     * @return 响应体 JSON 字符串
     * @throws IOException 请求失败时抛出
     */
    private String executeRequest(String url) throws IOException {
        IOException lastException = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return executeSingleRequest(url);
            } catch (IOException e) {
                lastException = e;
                if (attempt < 3) {
                    long delayMs = attempt * 1000L + (long) (Math.random() * 500);
                    log.warn("高德地图接口第{}次尝试失败，{}ms后重试：{}", attempt, delayMs, e.getMessage());
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw lastException;
    }

    private String executeSingleRequest(String url) throws IOException {
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
