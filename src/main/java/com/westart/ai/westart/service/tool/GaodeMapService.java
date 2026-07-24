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
    @Tool(value = """
            将用户提供的文字地址转换为精确的经纬度坐标。这是获取用户精确位置的【首选工具】。

            何时必须调用：
            - 用户说"我在深圳南山区科技园"、"我家在朝阳区建国路88号"等文字地址
            - 需要将地址转为坐标后才能进行周边搜索（searchAround）
            - 用户提到具体地名/路名/小区名/商圈名/写字楼名且需要分析该区域时
            - 任何需要精确经纬度的下游操作（周边分析、路线规划等）

            参数说明：
            - address（必填）：结构化地址字符串，越详细坐标越精确。
              正确示例："深圳市南山区科技园南路"、"北京市朝阳区建国路88号"、"杭州西湖区文三路"
              错误示例："深圳"（太模糊，只能定位城市中心）
            - city（选填）：指定搜索城市，用于缩小范围提高命中率。
              支持城市中文名（深圳）、citycode（0755）、adcode（440300）。
              如果已经知道用户所在城市，强烈建议传入此参数。

            典型用法：
            - 用户说"我在科技园" → 先追问城市 → geocode("深圳市南山区科技园", "深圳") → 拿到坐标
            - 用户说"朝阳区建国路88号" → geocode("北京市朝阳区建国路88号", "北京") → 拿到坐标
            - 拿到坐标后，可以继续调用 searchAround 分析周边环境

            注意：本工具不能替代用户的位置授权，需要用户主动提供地址信息。
            """)
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
    @Tool(value = """
            将经纬度坐标反查为人类可读的结构化地址描述。

            何时使用：
            - 有一个经纬度坐标，想知道这里具体是什么地方
            - 需要补全某个坐标的完整地址信息（省/市/区/街道/门牌号）
            - 需要知道某个坐标附近有哪些地标、商圈、POI
            - 配合 searchAround 使用：先搜到一批 POI，对感兴趣的 POI 坐标调用 regeo 获取详细地址

            参数说明：
            - location（必填）：经纬度坐标，格式为"经度,纬度"。如："116.473168,39.993015"
              经度在前，纬度在后，小数点不超过6位。

            返回内容：
            该坐标附近的完整地址（省份、城市、区县、街道、门牌号），以及周边的 AOI 和 POI 信息。

            注意：逆地理编码返回的是距离坐标点最近的地址信息，而非精确到门牌号的匹配。
            """)
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

    @Tool(value = """
            在城市或全国范围内按关键词搜索 POI（兴趣点），如奶茶店、星巴克、商场、地铁站等。

            何时使用：
            - 用户问"深圳有多少家星巴克"、"北京有什么好吃的川菜馆"
            - 用户需要分析某个城市区域内某类商户的分布情况
            - 用户问"XX附近有没有XX"，但尚未提供精确坐标时，可先用城市+关键词做初步搜索
            - 商业分析场景：如"我想在深圳开奶茶店，帮我看看现有竞争情况"

            参数说明：
            - keywords（必填）：搜索关键词，如"奶茶店"、"咖啡厅"、"商场"、"地铁站"、"银行"。
              规则：只支持一个关键词，如需搜索多个类型请分次调用。
            - city（选填）：限定搜索的城市或区域。
              支持：城市中文名（深圳）、citycode（0755）、adcode（440300）。
              不填则为全国搜索，但结果可能不够精准，建议尽量填写。
            - types（选填）：POI 分类编码或汉字分类，用于更精确的类型筛选。
              如："050000"（餐饮服务）、"060000"（购物服务）、"170000"（科教文化服务）。
              不填则由关键词自由匹配。
              多个类型用竖线分隔，如："050000|060000"。

            调用建议：
            - 如果用户给了具体地址，优先用 geocode 获取坐标，再用 searchAround 做周边搜索（更精确）
            - 如果用户只给了城市名，用本工具做城市级关键词搜索
            - 商业分析时，建议同时搜索目标品类和配套品类（如奶茶店 + 商场 + 写字楼）
            """)
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
    @Tool(value = """
            查询某个经纬度坐标周边指定范围内的 POI。

            何时使用：
            - 用户提供了精确地址并已通过 geocode 转换为坐标后，分析该位置周边环境
            - 用户问"我附近有什么好吃的"、"这周围有停车场吗"
            - 商业选址分析：拿到目标坐标后，搜索周边多大范围内有多少竞争对手/配套商户
            - 需要分析某个具体地点的商圈成熟度、人流量潜力的场景

            参数说明：
            - location（必填）：中心点经纬度，格式为"经度,纬度"，如："116.473168,39.993015"。
              通常由 geocode 转换得到。
            - keywords（选填）：搜索关键词，如"餐厅"、"银行"、"加油站"。
              不填则返回周边各类型 POI（默认涵盖餐饮、生活服务、商务住宅等）。
            - radius（选填）：搜索半径，单位米，范围 0-50000，不填默认 5000。
              建议：步行范围 500-1000，骑行范围 1000-3000，驾车范围 3000-10000。

            典型调用链路：
            1. 用户说"分析一下深圳南山区科技园适不适合开奶茶店"
            2. 先调用 geocode("深圳市南山区科技园", "深圳") → 拿到坐标
            3. 调用 searchAround(坐标, "奶茶店", "3000") → 周边 3km 竞品
            4. 调用 searchAround(坐标, "商场", "5000") → 周边 5km 商场（人流参考）
            5. 调用 searchAround(坐标, "写字楼", "3000") → 周边 3km 办公人群
            6. 综合以上数据分析商业可行性

            注意：必须已有坐标才能调用，如果用户只给了文字地址，请先用 geocode 转换。
            """)
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
    @Tool(value = """
            根据 IP 地址获取大致地理位置。仅支持国内 IPv4。

            【重要限制——请务必仔细阅读】
            1. 本工具定位精度仅为「城市级别」——最多知道用户可能在深圳、北京等城市范围内，
               无法定位到具体街道、小区或门牌号。绝不适用于打车、导航、精确距离计算等场景。
            2. 当不传 ip 参数时，调用方会自动使用"发起 HTTP 请求的来源 IP"进行定位。
               在本系统中，所有请求均由服务器端发出，因此自动定位到的是服务器机房所在地，
               而绝非用户的真实位置。
            3. 因此：当且仅当用户明确提供了一个具体的 IP 地址字符串时（例如用户说
               "帮我查一下 114.247.50.2 在哪"），本工具的返回结果才有意义。
               其他所有情况（如用户问"我在哪"、"帮我定位"、"看看我附近有什么"），
               都不能依赖本工具获取位置。

            参数说明：
            - ip（选填）：需要查询的 IPv4 地址。用户必须明确给出 IP 字符串才能传入。
              如果不填，将使用服务器请求来源 IP，不指向用户。

            正确的使用方式：
            - 用户说"帮我查一下这个 IP：114.247.50.2" → locateIP("114.247.50.2") ✅
            - 用户说"我在哪里" → 不能调用本工具，应主动询问用户所在城市 ❌
            - 用户说"看看我附近有什么" → 不能调用本工具，应请用户提供地址后使用 geocode + searchAround ❌
            """)
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
