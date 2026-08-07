package com.westart.ai.westart.reminder.entity;

/**
 * 提醒任务数据结构。
 *
 * @param id 提醒唯一标识
 * @param userId 微信用户ID
 * @param message 提醒消息正文
 * @param recurring 是否为周期性提醒
 * @param fireTimeEpochMs 一次性提醒的触发时间戳（毫秒），周期性提醒为0
 * @param cronExpression 周期性提醒的Cron表达式，一次性提醒为null
 */
public record ReminderTask(
        String id,
        String userId,
        String message,
        boolean recurring,
        long fireTimeEpochMs,
        String cronExpression
) {}