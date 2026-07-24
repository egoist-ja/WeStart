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
public class GaodeMapService {

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
    @Tool("地址转坐标。address=结构化地址（必填），city=城市限定（选填，中文名/citycode/adcode）")
    public String geocode(String address, String city) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德地理编码失败", e);
        }
    }

    //逆地理编码
    @Tool("坐标转地址。location=经纬度，格式\"经度,纬度\"（必填）")
    public String regeo(String location) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德逆地理编码失败", e);
        }
    }

    //POI关键字搜索

    @Tool("按关键词搜索兴趣点（旅游规划第一步用此工具搜景点）。keywords=关键词（必填），city=城市限定（选填），types=POI分类编码（选填，如050000=餐饮）")
    public String searchPOI(String keywords, String city, String types) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德POI关键字搜索失败", e);
        }
    }

    //POI周边搜索
    @Tool("搜索坐标周边兴趣点。location=中心点\"经度,纬度\"（必填），keywords=关键词（选填），radius=半径米（选填，默认5000，范围0-50000）")
    public String searchAround(String location, String keywords, String radius) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德POI周边搜索失败", e);
        }
    }
    //IP定位
    @Tool("IP定位，仅城市级精度、仅国内IPv4。ip=IPv4地址（选填，不填则用服务器IP，非用户位置）。仅用户明确给IP时使用")
    public String locateIP(String ip) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德IP定位失败", e);
        }
    }

    //驾车路线规划
    @Tool("驾车路线规划。origin=起点\"经度,纬度\"（必填），destination=终点\"经度,纬度\"（必填），strategy=0速度优先/1避收费/2最短/3避高速（选填），waypoints=途经点\"lng1,lat1;lng2,lat2\"（选填，最多16个）")
    public String driving(String origin, String destination, String strategy, String waypoints) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德驾车路线规划失败", e);
        }
    }

    //公交路线规划
    @Tool("公交/地铁路线规划。origin=起点\"经度,纬度\"（必填），destination=终点\"经度,纬度\"（必填），city=城市（必填），strategy=0最快捷/1最少换乘/2最少步行/3不乘地铁（选填）")
    public String transit(String origin, String destination, String city, String strategy) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德公交路线规划失败", e);
        }
    }

    //步行路线规划
    @Tool("步行路线规划。origin=起点\"经度,纬度\"（必填），destination=终点\"经度,纬度\"（必填）")
    public String walking(String origin, String destination) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德步行路线规划失败", e);
        }
    }

    //骑行路线规划
    @Tool("骑行路线规划。origin=起点\"经度,纬度\"（必填），destination=终点\"经度,纬度\"（必填）")
    public String bicycling(String origin, String destination) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德骑行路线规划失败", e);
        }
    }

    //距离测量
    @Tool("距离测量。origins=起点\"经度,纬度\"（必填），destination=终点\"经度,纬度\"（必填），type=0直线/1驾车/3步行（选填，默认0）")
    public String distance(String origins, String destination, String type) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德距离测量失败", e);
        }
    }

    //行政区域查询
    @Tool("行政区域查询。keywords=区域名称（必填），subdistrict=下级层级0~3（选填，0=不返回/1=下一级/2=下两级/3=下三级）")
    public String district(String keywords, String subdistrict) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德行政区域查询失败", e);
        }
    }

    //输入提示
    @Tool("地址输入提示，返回候选列表（不返回坐标）。keywords=关键词（必填），city=城市限定（选填）")
    public String inputTips(String keywords, String city) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德输入提示查询失败", e);
        }
    }

    //静态地图
    @Tool("生成静态地图图片链接。location=中心点\"经度,纬度\"（必填），zoom=1~17（选填，默认14），size=\"宽*高\"（选填，默认400*300），markers=标注点\"lng1,lat1;lng2,lat2\"（选填，最多10个）")
    public String staticMap(String location, String zoom, String size, String markers) {
        try {
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
                // 构建标注样式: mid,0xFF0000,A:坐标1;坐标2
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
        } catch (IllegalStateException e) {
            throw new RuntimeException("高德静态地图生成失败：API Key 未配置", e);
        }
    }

    //坐标转换
    @Tool("坐标转换至目标系。locations=坐标串\"lng,lat|lng,lat\"（必填），coordsys=源坐标系gps/mapbar/baidu（选填，默认gps）")
    public String coordinateConvert(String locations, String coordsys) {
        try {
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
        } catch (IOException e) {
            throw new RuntimeException("高德坐标转换失败", e);
        }
    }

    //通用HTTP请求
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
