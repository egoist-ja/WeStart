package com.westart.ai.westart.service.tool;

import com.westart.ai.westart.service.ReminderService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 定时提醒工具，供AI模型调用以创建、取消和查看定时提醒。
 *
 * <p>本类只承载工具定义与参数转发，业务逻辑（Redis持久化、
 * TaskScheduler调度、微信投递、启动恢复）位于
 * {@link ReminderService} 实现中。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderTool {

    private final ReminderService reminderService;

    /**
     * 创建一个一次性定时提醒，在指定分钟后通过微信通知用户。
     *
     * @param userId 当前微信用户ID，由框架自动注入
     * @param message 提醒消息正文
     * @param delayMinutes 延迟分钟数
     * @return 提醒创建结果，含提醒ID
     */
    @Tool(value = "创建一个一次性定时提醒，在指定分钟后通过微信消息通知用户。"
            + "适用于「X分钟后提醒我…」「帮我设个提醒…」等场景。"
            + "返回的提醒ID可用于后续取消。")
    public String createReminder(
            @ToolMemoryId String userId,
            @P("提醒消息正文，用自然亲切的口吻直接写出到时用户会收到的内容，如「该喝水啦，起来活动一下吧💧」") String message,
            @P("延迟分钟数，几分钟后提醒，例如10表示10分钟后") int delayMinutes) {
        log.info("[ReminderTool] createReminder 开始执行，userId={}，delayMinutes={}",
                userId, delayMinutes);
        return reminderService.createReminder(userId, message, delayMinutes);
    }

    /**
     * 创建一个周期性定时提醒，按Cron表达式重复触发。
     *
     * @param userId 当前微信用户ID，由框架自动注入
     * @param message 提醒消息正文
     * @param cronExpression Cron表达式
     * @return 提醒创建结果，含提醒ID
     */
    @Tool(value = "创建一个周期性定时提醒，按Cron表达式重复通过微信通知用户。"
            + "适用于「每天早上9点提醒我…」「每周一提醒我…」等场景。"
            + "常用Cron：每天9点=0 0 9 * * * 每周一9点=0 0 9 * * 1 "
            + "每小时=0 0 * * * *。返回的提醒ID可用于后续取消。")
    public String createRepeatingReminder(
            @ToolMemoryId String userId,
            @P("提醒消息正文，用自然亲切的口吻直接写出到时用户会收到的内容") String message,
            @P("Cron表达式，六位（秒 分 时 日 月 周），如0 0 9 * * *表示每天早上9点") String cronExpression) {
        log.info("[ReminderTool] createRepeatingReminder 开始执行，userId={}，cron={}",
                userId, cronExpression);
        return reminderService.createRepeatingReminder(userId, message, cronExpression);
    }

    /**
     * 取消指定ID的提醒。
     *
     * @param userId 当前微信用户ID，由框架自动注入
     * @param reminderId 提醒ID
     * @return 取消结果
     */
    @Tool(value = "取消指定ID的定时提醒。reminderId来自创建提醒时的返回值，"
            + "或通过「查看提醒」工具获取。")
    public String cancelReminder(
            @ToolMemoryId String userId,
            @P("提醒ID，创建提醒时返回的唯一标识") String reminderId) {
        log.info("[ReminderTool] cancelReminder 开始执行，userId={}，reminderId={}",
                userId, reminderId);
        return reminderService.cancelReminder(userId, reminderId);
    }

    /**
     * 查看当前用户所有活跃的提醒。
     *
     * @param userId 当前微信用户ID，由框架自动注入
     * @return 提醒列表
     */
    @Tool(value = "查看当前用户设置的所有活跃提醒，包括一次性提醒和周期性提醒。"
            + "返回每个提醒的ID、类型（单次/周期）、触发时间和内容，"
            + "供用户选择要取消的提醒。")
    public String listReminders(@ToolMemoryId String userId) {
        log.info("[ReminderTool] listReminders 开始执行，userId={}", userId);
        return reminderService.listReminders(userId);
    }
}
