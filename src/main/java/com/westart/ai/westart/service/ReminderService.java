package com.westart.ai.westart.service;

/**
 * 定时提醒业务服务。
 *
 * <p>负责一次性提醒与周期性提醒的创建、取消和查询，
 * 包含 Redis 持久化、TaskScheduler 调度与微信消息投递。
 * 该接口不依赖 LangChain4j 工具注解，供工具层与其它业务复用。</p>
 */
public interface ReminderService {

    /**
     * 创建一个一次性定时提醒，在指定分钟后通过微信通知用户。
     *
     * @param userId 微信用户ID
     * @param message 提醒消息正文
     * @param delayMinutes 延迟分钟数
     * @return 提醒创建结果，含提醒ID
     * @throws IllegalArgumentException userId、message 无效或延迟分钟数越界时抛出
     */
    String createReminder(String userId, String message, int delayMinutes);

    /**
     * 创建一个周期性定时提醒，按Cron表达式重复触发。
     *
     * @param userId 微信用户ID
     * @param message 提醒消息正文
     * @param cronExpression 六位 Cron 表达式
     * @return 提醒创建结果，含提醒ID
     * @throws IllegalArgumentException userId、message 或 cronExpression 无效时抛出
     */
    String createRepeatingReminder(String userId, String message, String cronExpression);

    /**
     * 取消指定ID的提醒。
     *
     * @param userId 微信用户ID
     * @param reminderId 提醒ID
     * @return 取消结果
     * @throws IllegalArgumentException reminderId 为空时抛出
     */
    String cancelReminder(String userId, String reminderId);

    /**
     * 查看当前用户所有活跃的提醒。
     *
     * @param userId 微信用户ID
     * @return 提醒列表文本
     */
    String listReminders(String userId);
}
