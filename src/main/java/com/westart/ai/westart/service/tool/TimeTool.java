package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class TimeTool {

    @Tool(value="获取指定时区的当前日期和时间。当用户询问有关时间的问题或强时效的问题时调用此工具。",
    searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String getCurrentTime(String timeZone) {
        log.info("调用时间工具，timeZone={}",
                timeZone == null || timeZone.isBlank() ? "系统默认" : timeZone);
        try {
            ZoneId zoneId = (timeZone == null || timeZone.isBlank())
                    ? ZoneId.systemDefault()
                    : ZoneId.of(timeZone);

            ZonedDateTime now = ZonedDateTime.now(zoneId);
            String result = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
            log.info("时间工具返回成功，result={}", result);
            return result;
        } catch (Exception e) {
            log.error("时间工具调用失败，timeZone={}", timeZone, e);
            return "无法识别的时区: " + timeZone + "，请提供有效的IANA时区ID（如 Asia/Shanghai）";
        }
    }
}
