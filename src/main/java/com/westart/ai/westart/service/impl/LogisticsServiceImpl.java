package com.westart.ai.westart.service.impl;

import com.westart.ai.westart.service.LogisticsService;
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

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class LogisticsServiceImpl implements LogisticsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogisticsServiceImpl.class);
    private static final String UAPI_BASE_URL = "uapis.cn";

    private final OkHttpClient okHttpClient;

    @Override
    @Tool(value = "当用户需要查询快递物流信息时调用该工具。" +
        "trackingNumber表示快递单号（必填，通常是一串10-20位的数字或字母数字组合），" +
        "carrierCode表示快递公司编码（选填，不填则系统自动识别快递公司），" +
        "phone表示收件人手机尾号后4位（选填，部分快递公司如顺丰需要验证手机尾号才能查询），" +
        "如果用户没有提供快递单号，则不调用该方法，并提示用户提供快递单号")
    public String queryLogistics(String trackingNumber, String carrierCode, String phone) {
        try {
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
            LOGGER.info("查询快递物流，trackingNumber={}，carrierCode={}，phone={}",
                    trackingNumber,
                    carrierCode == null || carrierCode.isBlank() ? "自动识别" : carrierCode,
                    phone == null || phone.isBlank() ? "未提供" : phone);

            String apiKey = System.getenv("UAPIS_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("环境变量 UAPIS_API_KEY 未设置");
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
                    LOGGER.error("快递查询接口请求失败，HTTP {}: {}", response.code(), body);
                    throw new IOException("快递查询接口请求失败，HTTP "
                            + response.code() + ": " + body);
                }
                LOGGER.info("快递物流查询成功，trackingNumber={}", trackingNumber);
                return body;
            }

        } catch (IOException e) {
            throw new RuntimeException("查询快递物流信息失败", e);
        }
    }

}
