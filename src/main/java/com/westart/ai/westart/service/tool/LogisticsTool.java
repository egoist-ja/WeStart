package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.P;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class LogisticsTool {

    private static final String UAPI_BASE_URL = "uapis.cn";

    private final OkHttpClient okHttpClient;

    @Tool(value = "查询快递包裹的承运公司、当前物流状态和运输轨迹。"
            + "trackingNumber为快递单号，必填；carrierCode为快递公司编码，选填，"
            + "不填时自动识别；phone为收件人手机尾号后4位，部分承运公司查询时需要。"
            + "用户未提供快递单号时不要调用。")
    public String queryLogistics(
            @P("快递单号，必填，通常是一串10-20位的数字或字母数字组合") String trackingNumber,
            @P("快递公司编码，选填，常见的有SF(顺丰)、YTO(圆通)、ZTO(中通)、YD(韵达)、STO(申通)等，不填则系统自动识别") String carrierCode,
            @P("收件人手机尾号后4位，选填，部分快递公司如顺丰需要验证手机尾号才能查询") String phone) throws IOException {
        HttpUrl.Builder urlBuilder = new HttpUrl.Builder()
                .scheme("https")
                .host(UAPI_BASE_URL)
                .addPathSegments("api/v1/misc/tracking/query")
                .addQueryParameter("tracking_number", trackingNumber);

        if (carrierCode != null && !carrierCode.isBlank()) {
            urlBuilder.addQueryParameter("carrier_code", carrierCode);
        }
        if (phone != null && !phone.isBlank()) {
            urlBuilder.addQueryParameter("phone", phone);
        }

        String url = urlBuilder.build().toString();
        log.info("查询快递物流，trackingNumber={}，carrierCode={}，phone={}",
                trackingNumber,
                carrierCode == null || carrierCode.isBlank() ? "自动识别" : carrierCode,
                phone == null || phone.isBlank() ? "未提供" : phone);

        String apiKey = System.getenv("UAPI_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("UAPI_KEY 环境变量未设置");
        }

        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String body = responseBody == null ? "" : responseBody.string();

            if (!response.isSuccessful()) {
                log.error("快递查询接口返回错误，trackingNumber={}，HTTP {}，响应：{}",
                        trackingNumber, response.code(), body);
                throw new IOException("快递查询接口请求失败，HTTP "
                        + response.code() + ": " + body);
            }
            log.info("快递物流查询成功，trackingNumber={}", trackingNumber);
            return body;
        }
    }

}
