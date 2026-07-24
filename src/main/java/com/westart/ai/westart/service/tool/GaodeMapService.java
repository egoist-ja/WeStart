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
            将用户提供的文字地址转换为精确的经纬度坐标。这是获取用户精确位置的【首选工具】，
            也是旅游规划和出行路线中 searchPOI 之后必须调用的第二步。

            何时必须调用：
            - 用户说"我在深圳南山区科技园"、"我家在朝阳区建国路88号"等文字地址
            - 旅游规划时，拿到 searchPOI 返回的景点名称后，必须逐一调用本工具获取坐标
            - 出行路线规划前，起终点必须先用本工具转换为坐标
            - 任何需要精确经纬度的下游操作（周边搜索、路线规划等）

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
            在城市或全国范围内按关键词搜索 POI（兴趣点）。

            【旅游规划强制入口】
            任何旅游、旅行、出游规划请求（如「帮我规划XX游」「XX几日游怎么安排」
            「想去XX玩」「XX有什么好玩的」），第一步必须先调用本工具搜索目的地景点。
            即使你知道该目的地有哪些景点，也必须通过本工具获取真实 POI 数据再规划。
            严禁跳过本工具直接用训练知识拼凑行程。

            何时使用：
            - 用户请求旅游规划、行程推荐 → 这是强制第一步，必须调本工具搜景点
            - 用户问"深圳有多少家星巴克"、"北京有什么好吃的川菜馆"
            - 用户需要分析某个城市区域内某类商户的分布情况
            - 用户问"XX附近有没有XX"，但尚未提供精确坐标时，可先用城市+关键词做初步搜索
            - 商业分析场景：如"我想在深圳开奶茶店，帮我看看现有竞争情况"

            参数说明：
            - keywords（必填）：搜索关键词。
              旅游场景：先用"景点"搜一次，再用"酒店"搜一次，再用"餐厅"搜一次
              商业场景：如"奶茶店"、"咖啡厅"、"商场"、"地铁站"、"银行"
              规则：只支持一个关键词，多个类型请分次调用
            - city（选填）：限定搜索城市。旅游规划必须传入目的地城市/省份名。
              如用户说"云南七日游"，传 city="云南"；"丽江三日游"，传 city="丽江"
            - types（选填）：POI 分类编码，如"050000"（餐饮）、"060000"（购物）

            旅游规划标准工作流：
            searchPOI("景点", "云南") → 拿到景点列表 → 对每个景点 geocode 获取坐标
            → driving(坐标, 坐标, null, "途经点1;途经点2") 规划路线
            → searchAround(景点坐标, "酒店", 3000) 查住宿
            → searchAround(景点坐标, "餐厅", 1000) 查餐饮

            注意：不填 city 则全国搜索，结果可能不准，旅游场景必须填 city。
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

    //驾车路线规划
    @Tool(value = """
            根据起终点经纬度坐标规划驾车路线，返回预计行驶距离、预计耗时和详细路线步骤。

            何时必须调用：
            - 用户问"从A到B开车要多久"、"开车从A去B怎么走"
            - 用户需要自驾出行方案，包括预计时间、距离、路线说明
            - 用户想比较不同出行方式时（与其他路线工具配合）

            前置条件 —— 极其重要：
            1. 起点和终点必须是精确的"经度,纬度"坐标，不能是文字地址
            2. 如果用户只提供了文字地址（如"科技园"、"北京西站"），请先用 geocode 转换
            3. 如果用户提供的位置模糊（如"市中心"、"我家附近"、"那边"），不要调用本工具
               —— 请先追问用户获取具体地址或明确地标，然后用 geocode 获取坐标
            4. 如果 geocode 返回的坐标不够精确（如只定位到城市中心），应告知用户地址不够详细

            参数说明：
            - origin（必填）：起点坐标，格式为"经度,纬度"。由 geocode 转换得到。
            - destination（必填）：终点坐标，格式为"经度,纬度"。由 geocode 转换得到。
            - strategy（选填）：路径计算策略。
              0=速度优先/推荐（默认），1=避免收费，2=最短距离，
              3=不走高速（仅限低速），4=避免拥堵且躲避收费
            - waypoints（选填）：途经点坐标串，格式为"经度1,纬度1;经度2,纬度2"。
              多个途经点用分号分隔，最多支持16个途经点。
              途经顺序 = 传入顺序，终点不变。
              这是实现多景点路线规划的核心参数。

            典型调用链路：
            - 简单路线：
              1. 用户说"从深圳科技园开车去宝安机场要多久"
              2. geocode("深圳市南山区科技园", "深圳") → 起点坐标
              3. geocode("深圳市宝安国际机场", "深圳") → 终点坐标
              4. driving(起点坐标, 终点坐标) → 距离、时间、路线

            - 旅游多景点路线：
              1. 用户说"从酒店出发，先去世界之窗，再去华侨城，最后回酒店"
              2. geocode("深圳XX酒店", "深圳") → 起点坐标
              3. geocode("深圳世界之窗", "深圳") → 途经点1坐标
              4. geocode("深圳华侨城", "深圳") → 途经点2坐标
              5. driving(起点坐标, 起点坐标, null, "途经点1;途经点2")
                 → 完整的环形路线，含总距离、总耗时及各段详情

            边缘情况处理：
            - 如果用户地址定位结果不精确，应告知并请求更具体的地址
            - 如果起终点相同或非常接近，应提醒用户
            - 如果路线结果中 distance=0 或 routes 为空，说明地址解析有问题
            - 途经点过多（>16个）时，建议用户分天规划
            """)
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
    @Tool(value = """
            根据起终点经纬度坐标规划公交/地铁出行路线，返回换乘方案、预计耗时和详细步骤。

            何时必须调用：
            - 用户问"从A到B坐地铁怎么走"、"坐公交怎么去XX"
            - 用户需要公交、地铁或公交换乘方案
            - 用户想比较公共交通与驾车的出行时间

            前置条件 —— 极其重要：
            1. 起点和终点必须是精确的"经度,纬度"坐标
            2. 如果只有文字地址，请先用 geocode 转换
            3. city 参数必须填写，公交路线依赖城市范围进行搜索
            4. 如果用户位置模糊，请先追问后再调用

            参数说明：
            - origin（必填）：起点坐标，"经度,纬度"
            - destination（必填）：终点坐标，"经度,纬度"
            - city（必填）：起点所在城市名称或城市编码。
              如果起点和终点不在同一城市，填写起点城市。
              支持：城市中文名（深圳）、citycode（0755）、adcode（440300）
            - strategy（选填）：乘车方案策略。
              0=最快捷（默认），1=最少换乘，2=最少步行，3=不乘地铁

            边缘情况处理：
            - 如果公交方案 count=0，说明这两个地点间没有公共交通，建议改用 driving
            - 如果跨城市距离过远（>100km），公交可能无法规划，建议改用 driving
            - 座标来源不够精确会导致方案偏差，请确认地址后再调用
            """)
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
    @Tool(value = """
            根据起终点经纬度坐标规划步行路线，返回步行距离、预计步行时间和路线步骤。

            何时使用：
            - 用户问"走过去要多久"、"从A走到B怎么走"
            - 短距离出行（一般 5km 以内，超过建议改用骑行或驾车）
            - 需要了解两个地点之间的步行距离

            前置条件：
            1. 起终点必须是精确的"经度,纬度"坐标
            2. 如果只有文字地址，先用 geocode 转换
            3. 两点间直线距离通常不应超过 50km（超过则步行不可行）

            参数说明：
            - origin（必填）：起点坐标，"经度,纬度"
            - destination（必填）：终点坐标，"经度,纬度"

            边缘情况处理：
            - 如果步行距离 >5km，应提醒用户步行时间较长，建议 alternative 交通方式
            - 如果路线规划失败（如跨水域），建议改用其他出行方式
            """)
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
    @Tool(value = """
            根据起终点经纬度坐标规划骑行路线，返回骑行距离、预计骑行时间和路线步骤。

            何时使用：
            - 用户问"骑车要多久"、"骑共享单车怎么走"
            - 中短距离出行（一般 1km 至 20km 之间）
            - 需要比较骑行与步行/驾车的时间差异

            前置条件：
            1. 起终点必须是精确的"经度,纬度"坐标
            2. 只有文字地址时，先用 geocode 转换
            3. 两点间距离不宜超过 50km

            参数说明：
            - origin（必填）：起点坐标，"经度,纬度"
            - destination（必填）：终点坐标，"经度,纬度"

            边缘情况处理：
            - 如果骑行距离 >20km，应提醒用户距离较远
            - 如果路线失败，可能是路径不适合骑行（高速公路等），建议改用其他方式
            """)
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
    @Tool(value = """
            测量起点到终点的距离，支持直线距离、驾车距离或步行距离三种方式。

            何时使用：
            - 用户问"A离B多远"、"A和B之间的距离是多少"
            - 用户只需距离数值而不需要完整路线方案时
            - 快速比较多个地点与目标地点的距离

            前置条件：
            1. 起点和终点必须是精确的"经度,纬度"坐标
            2. 只有文字地址时，先用 geocode 转换后调用

            参数说明：
            - origins（必填）：起点坐标，"经度,纬度"
            - destination（必填）：终点坐标，"经度,纬度"
            - type（选填）：距离计算方式。
              0=直线距离（默认），1=驾车导航距离，3=步行规划距离

            注意：驾车/步行距离基于实际路网（含绕行），直线距离仅基于两点坐标计算。
            """)
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
    @Tool(value = """
            查询中国行政区域信息，包括省份、城市、区县、街道的边界、下级行政区列表和中心点。

            何时使用：
            - 用户问"深圳有哪些区"、"南山区包含哪些街道"、"广东省下辖哪些市"
            - 商业分析前需要了解区域划分：如"想在深圳南山开奶茶店"→ 调 district("南山区", "1") 查看下辖街道
            - 需要确认某个地名属于哪个行政区
            - 需要获取行政区的边界坐标（用于后续可视化）

            参数说明：
            - keywords（必填）：行政区名称，支持全称或关键字。
              如："深圳市"、"南山区"、"粤海街道"、"山东省"
            - subdistrict（选填）：显示下级行政区层级。
              0=不返回下级（默认），1=返回下一级，2=返回下两级，3=返回下三级
              例：district("广东省", "1") → 列出所有地级市
              例：district("深圳市", "2") → 列出所有区 + 各区下辖街道

            商业选址场景：
            district("深圳市南山区", "1") → 获取街道列表
            → 对每个街道用 geocode + searchAround 分析竞品密度
            → 帮助用户选择最优街道

            边缘情况处理：
            - 如果 keywords 太模糊（如"南山"而非"深圳市南山区"），应指定上级城市
            - 如果返回的 districts 为空，可能是名称不精确，建议用户确认完整名称
            """)
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
    @Tool(value = """
            根据用户输入的部分地址关键词，返回可能的地址补全建议列表。

            何时使用：
            - 用户输入了模糊地址（如"科技园"），需要列出"深圳科技园/北京科技园/广州科技园"让用户选择
            - 用户在描述位置时提到不完整的名称，需要确定具体是哪个地方
            - geocode 返回了多个可能位置时，辅助用户确认是哪一个
            - 用户说"南山那边的XX"，不确定具体地址，先列候选

            参数说明：
            - keywords（必填）：用户输入的部分地址关键词。如"科技园"、"万达广场"、"朝阳"
            - city（选填）：限定搜索城市，强烈建议填写以提高命中率。
              如用户说"深圳科技园"，可传 city="深圳"

            工作流程建议：
            1. 用户提供模糊地址 → 调用 inputTips 获取候选列表
            2. 列出 3-5 个最相关候选项让用户确认
            3. 用户选择后 → 用 geocode 获取该选项的精确坐标
            4. 继续后续分析

            注意：本工具只返回地址名称和建议，不返回坐标。
            拿到用户确认的地址后请用 geocode 获取坐标。
            """)
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
    @Tool(value = """
            根据指定的中心坐标、缩放级别和标注点，生成一张高德静态地图图片的访问链接。

            何时使用：
            - 用户需要查看某个位置的地图："帮我看一下这里的地图"
            - 分析结果需要可视化呈现：如 POI 分布、路线概览
            - 选址分析后，用户想直观看到目标位置周边环境
            - 用户说"给我看地图"、"发个地图过来"

            前置条件：
            1. location 必须是精确的"经度,纬度"坐标
            2. 如果只有地址，请先用 geocode 转换

            参数说明：
            - location（必填）：地图中心点坐标，"经度,纬度"
            - zoom（选填）：地图缩放级别，1-17。1=全国，10=城市级别，14=街道级别，17=建筑级别
              不填默认 14（适合查看街区周边）
            - size（选填）：图片尺寸，格式"宽*高"，如"400*300"、"600*400"
              不填默认"400*300"
            - markers（选填）：在地图上标注的 POI 点，格式为"经度,纬度;经度,纬度..."
              多个坐标用分号分隔，最多 10 个标注点
              如："116.473168,39.993015;116.483168,39.983015"

            返回说明：
            本工具返回一个可直接在浏览器中打开的图片 URL。
            收到 URL 后，请将链接直接发给用户，用户可点击查看地图。
            同时应向用户简要描述地图展示的内容（中心位置、标注了哪些点）。

            注意：生成的 URL 中已包含 API Key，属于内部链接，发送给用户不受影响。
            """)
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
    @Tool(value = """
            将其他坐标系（如 GPS 原始坐标、百度坐标）转换为高德坐标系，或在不同坐标系间互转。

            何时使用：
            - 用户提供一个非高德来源的坐标（如手机 GPS 经纬度、其他地图的坐标）
            - 从外部数据源获取的坐标在 searchAround/driving 中使用前需要转换
            - 坐标在高德地图上显示位置与实际不符时需要转换

            常见场景：
            - 用户分享手机 GPS 定位："我在经度114.xxx，纬度22.xxx" → 先 convert 再使用
            - 从百度地图复制的坐标 → coordsys="baidu"
            - 从 Mapbar 地图获取的坐标 → coordsys="mapbar"

            参数说明：
            - locations（必填）：需要转换的坐标，多个坐标用"|"分隔。
              格式："经度,纬度" 或 "经度,纬度;经度,纬度"
              如："116.473168,39.993015" 或 "116.473168,39.993015;116.483168,39.983015"
            - coordsys（选填）：源坐标系类型。
              gps（GPS原始坐标，默认）、mapbar、baidu（百度坐标）、autonavi（高德坐标，无需转换）

            注意：转换后的坐标可直接用于 searchAround、driving 等工具。
            """)
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
