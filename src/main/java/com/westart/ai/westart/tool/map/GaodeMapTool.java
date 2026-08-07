package com.westart.ai.westart.tool.map;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

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
    private static final String DEFAULT_STATIC_MAP_ZOOM = "14";
    private static final String DEFAULT_STATIC_MAP_SIZE = "400*300";
    private static final int MAX_STATIC_MAP_SIZE = 1024;
    private static final int MAX_STATIC_MAP_MARKERS = 10;
    private static final int MAX_STATIC_MAP_LABEL_LENGTH = 15;
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
//    @Tool(returnBehavior = ReturnBehavior.IMMEDIATE, value = """
//            根据中心点和标注点生成静态地图图片，并将图片直接发送给用户。
//            仅在用户明确需要查看地图图片时调用；本工具不执行地点搜索或路线规划。
//            location为中心点“经度,纬度”，必填；zoom为1至17的整数，选填，默认14。
//            size格式为“宽度*高度”，选填，默认400*300，宽高均不得超过1024。
//            markers格式为“经度,纬度,名称|经度,纬度,名称”，选填，最多10个；
//            名称不得包含URL、分隔符或Emoji，不要传入高德markers样式。
//            """)
    public List<Image> staticMap(
            @P("中心点坐标，格式为“经度,纬度”") String location,
            @P(value = "缩放级别，1至17的整数，选填，默认14", required = false) String zoom,
            @P(value = "图片尺寸，格式为“宽度*高度”，选填，默认400*300", required = false)
            String size,
            @P(value = "标注点，格式为“经度,纬度,名称|经度,纬度,名称”，选填，最多10个", required = false)
            String markers) {
        String zoomValue = zoom == null || zoom.isBlank()
                ? DEFAULT_STATIC_MAP_ZOOM : zoom.trim();
        String sizeValue = normalizeStaticMapSize(size);
        validateZoom(zoomValue);

        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(AMAP_BASE_URL)
                .addPathSegments("v3/staticmap")
                .addQueryParameter("key", getApiKey())
                .addQueryParameter("location", location)
                .addQueryParameter("zoom", zoomValue)
                .addQueryParameter("size", sizeValue);
        addStaticMapMarkers(urlBuilder, markers);
        return List.of(downloadStaticMap(urlBuilder.build()));
    }

    private void validateZoom(String zoom) {
        try {
            int zoomValue = Integer.parseInt(zoom);
            if (zoomValue < 1 || zoomValue > 17) {
                throw new IllegalArgumentException("静态地图缩放级别必须在1至17之间");
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("静态地图缩放级别必须是整数", exception);
        }
    }

    private String normalizeStaticMapSize(String size) {
        String sizeValue = size == null || size.isBlank()
                ? DEFAULT_STATIC_MAP_SIZE
                : size.trim().replace('x', '*').replace('X', '*').replace('×', '*');
        String[] sizeParts = sizeValue.split("\\*", -1);
        if (sizeParts.length != 2) {
            throw new IllegalArgumentException("静态地图尺寸格式应为“宽度*高度”");
        }
        try {
            int width = Integer.parseInt(sizeParts[0]);
            int height = Integer.parseInt(sizeParts[1]);
            if (width < 1 || width > MAX_STATIC_MAP_SIZE
                    || height < 1 || height > MAX_STATIC_MAP_SIZE) {
                throw new IllegalArgumentException("静态地图宽高必须在1至1024之间");
            }
            return width + "*" + height;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("静态地图宽高必须是整数", exception);
        }
    }

    private void addStaticMapMarkers(HttpUrl.Builder urlBuilder, String markers) {
        if (markers == null || markers.isBlank()) {
            return;
        }
        String[] markerParts = markers.split("\\|", -1);
        if (markerParts.length > MAX_STATIC_MAP_MARKERS) {
            throw new IllegalArgumentException("静态地图标注点不能超过10个");
        }

        List<String> markerParameters = new ArrayList<>(markerParts.length);
        List<String> labelParameters = new ArrayList<>(markerParts.length);
        for (int index = 0; index < markerParts.length; index++) {
            String[] values = markerParts[index].split(",", 3);
            if (values.length < 2) {
                throw new IllegalArgumentException("静态地图标注点格式错误");
            }
            String markerLocation = values[0].trim() + "," + values[1].trim();
            markerParameters.add("mid,0xFF0000," + (char) ('A' + index)
                    + ":" + markerLocation);
            if (values.length == 3) {
                String name = normalizeMarkerName(values[2]);
                if (!name.isBlank()) {
                    labelParameters.add(name + ",0,1,14,0xFFFFFF,0xFF0000:"
                            + markerLocation);
                }
            }
        }
        urlBuilder.addQueryParameter("markers", String.join("|", markerParameters));
        if (!labelParameters.isEmpty()) {
            urlBuilder.addQueryParameter("labels", String.join("|", labelParameters));
        }
    }

    private String normalizeMarkerName(String markerName) {
        StringBuilder nameBuilder = new StringBuilder();
        markerName.trim().codePoints()
                .filter(codePoint -> Character.isLetterOrDigit(codePoint)
                        || Character.isWhitespace(codePoint)
                        || "-_·()（）".indexOf(codePoint) >= 0)
                .limit(MAX_STATIC_MAP_LABEL_LENGTH)
                .forEach(nameBuilder::appendCodePoint);
        return nameBuilder.toString().trim();
    }

    private Image downloadStaticMap(HttpUrl mapUrl) {
        Request request = new Request.Builder()
                .url(mapUrl)
                .get()
                .header("Accept", "image/*")
                .build();
        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            if (!response.isSuccessful() || responseBody == null) {
                log.warn("高德静态地图请求失败，HTTP状态码={}", response.code());
                throw new IllegalStateException(
                        "高德静态地图请求失败，HTTP状态码=" + response.code());
            }
            MediaType contentType = responseBody.contentType();
            byte[] imageBytes = responseBody.bytes();
            if (contentType == null
                    || !"image".equalsIgnoreCase(contentType.type())
                    || imageBytes.length == 0) {
                log.warn("高德静态地图响应无效，内容类型={}，响应长度={}",
                        contentType, imageBytes.length);
                throw new IllegalStateException("高德静态地图接口未返回有效图片");
            }
            return Image.builder()
                    .base64Data(Base64.getEncoder().encodeToString(imageBytes))
                    .mimeType(contentType.type() + "/" + contentType.subtype())
                    .build();
        } catch (SocketTimeoutException exception) {
            log.warn("高德静态地图请求超时", exception);
            throw new IllegalStateException("高德静态地图请求超时，请稍后重试", exception);
        } catch (IOException exception) {
            log.error("高德静态地图网络请求失败", exception);
            throw new IllegalStateException("高德静态地图网络请求失败，请稍后重试", exception);
        }
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
