package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class TimeTool {

    @Tool("获取指定时区的当前日期和时间。当用户询问有关时间的问题或强时效的问题时调用此工具。")
    public String getCurrentTime(String timeZone) {
        try {
            ZoneId zoneId = (timeZone == null || timeZone.isBlank())
                    ? ZoneId.systemDefault()
                    : ZoneId.of(timeZone);

            ZonedDateTime now = ZonedDateTime.now(zoneId);
            return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
        } catch (Exception e) {
            return "无法识别的时区: " + timeZone + "，请提供有效的IANA时区ID（如 Asia/Shanghai）";
        }
    }
}
