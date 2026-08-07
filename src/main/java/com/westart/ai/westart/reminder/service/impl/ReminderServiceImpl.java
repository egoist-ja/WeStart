package com.westart.ai.westart.reminder.service.impl;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.google.gson.Gson;
import com.westart.ai.westart.reminder.entity.ReminderTask;
import com.westart.ai.westart.reminder.service.ReminderService;
import com.westart.ai.westart.wechat.service.impl.ILinkClientSessionRegistry;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 定时提醒业务服务实现。
 *
 * <p>负责提醒的持久化（Redis）、定时调度（TaskScheduler）、
 * 微信消息投递和应用启动时的任务恢复。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderServiceImpl implements ReminderService {

    private static final String REMINDER_KEY_PREFIX = "westart:reminder:";
    private static final Duration REMINDER_TTL_BUFFER = Duration.ofMinutes(5);
    private static final int MAX_DELAY_MINUTES = 1440;

    private final RedisTemplate<String, String> redisTemplate;
    private final Gson gson;
    private final TaskScheduler taskScheduler;
    private final ILinkClientSessionRegistry sessionRegistry;

    /**
     * 活跃的定时任务Future映射，用于取消操作。
     */
    private final Map<String, ScheduledFuture<?>> scheduledFutures =
            new ConcurrentHashMap<>();

    /**
     * 创建一个一次性定时提醒，在指定分钟后通过微信通知用户。
     *
     * @param userId 微信用户ID
     * @param message 提醒消息正文
     * @param delayMinutes 延迟分钟数
     * @return 提醒创建结果，含提醒ID
     */
    @Override
    public String createReminder(String userId, String message, int delayMinutes) {
        log.info("[ReminderService] createReminder 开始执行，userId={}，delayMinutes={}",
                userId, delayMinutes);
        validateCommon(userId, message);
        if (delayMinutes < 1 || delayMinutes > MAX_DELAY_MINUTES) {
            throw new IllegalArgumentException(
                    "延迟分钟数必须在1-" + MAX_DELAY_MINUTES + "之间");
        }

        String reminderId = UUID.randomUUID().toString();
        Instant fireTime = Instant.now().plus(Duration.ofMinutes(delayMinutes));
        ReminderTask task = new ReminderTask(
                reminderId, userId, message, false,
                fireTime.toEpochMilli(), null);

        persistAndSchedule(task, fireTime, delayMinutes);
        String result = "已设置提醒，ID=" + reminderId
                + "，" + delayMinutes + "分钟后提醒：" + message;
        log.info("[ReminderService] createReminder 执行成功，userId={}", userId);
        return result;
    }

    /**
     * 创建一个周期性定时提醒，按Cron表达式重复触发。
     *
     * @param userId 微信用户ID
     * @param message 提醒消息正文
     * @param cronExpression Cron表达式
     * @return 提醒创建结果，含提醒ID
     */
    @Override
    public String createRepeatingReminder(String userId, String message, String cronExpression) {
        log.info("[ReminderService] createRepeatingReminder 开始执行，userId={}，cron={}",
                userId, cronExpression);
        validateCommon(userId, message);
        if (StringUtils.isBlank(cronExpression)) {
            throw new IllegalArgumentException("Cron表达式不能为空");
        }

        String reminderId = UUID.randomUUID().toString();
        ReminderTask task = new ReminderTask(
                reminderId, userId, message, true, 0, cronExpression);

        String taskJson = gson.toJson(task);
        redisTemplate.opsForValue().set(reminderKey(reminderId), taskJson);
        log.info("周期性提醒已存入Redis，reminderId={}，userId={}，cron={}",
                reminderId, userId, cronExpression);

        CronTrigger trigger = new CronTrigger(
                cronExpression, ZoneId.systemDefault());
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> fireReminder(reminderId), trigger);
        scheduledFutures.put(reminderId, future);
        log.info("周期性提醒已注册，reminderId={}，cron={}", reminderId, cronExpression);

        String result = "已设置周期性提醒，ID=" + reminderId
                + "，频率：" + cronExpression + "，内容：" + message;
        log.info("[ReminderService] createRepeatingReminder 执行成功，userId={}", userId);
        return result;
    }

    /**
     * 取消指定ID的提醒。
     *
     * @param userId 微信用户ID
     * @param reminderId 提醒ID
     * @return 取消结果
     */
    @Override
    public String cancelReminder(String userId, String reminderId) {
        log.info("[ReminderService] cancelReminder 开始执行，userId={}，reminderId={}",
                userId, reminderId);
        if (StringUtils.isBlank(reminderId)) {
            throw new IllegalArgumentException("提醒ID不能为空");
        }

        ScheduledFuture<?> future = scheduledFutures.remove(reminderId);
        if (future != null) {
            future.cancel(false);
        }

        boolean deleted = Boolean.TRUE.equals(
                redisTemplate.delete(reminderKey(reminderId)));
        if (deleted) {
            log.info("提醒已取消，reminderId={}", reminderId);
        } else {
            log.warn("取消失败，提醒不存在，reminderId={}", reminderId);
        }

        log.info("[ReminderService] cancelReminder 执行完成，userId={}，cancelled={}",
                userId, deleted);
        return deleted
                ? "已取消提醒：" + reminderId
                : "取消失败，提醒不存在或已过期：" + reminderId;
    }

    /**
     * 查看当前用户所有活跃的提醒。
     *
     * @param userId 微信用户ID
     * @return 提醒列表
     */
    @Override
    public String listReminders(String userId) {
        log.info("[ReminderService] listReminders 开始执行，userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return "当前没有活跃的提醒。";
        }

        Set<String> keys = redisTemplate.keys(REMINDER_KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return "当前没有活跃的提醒。";
        }

        List<ReminderTask> userTasks = new ArrayList<>();
        for (String key : keys) {
            ReminderTask task = loadTask(key);
            if (task != null && userId.equals(task.userId())) {
                userTasks.add(task);
            }
        }

        if (userTasks.isEmpty()) {
            return "当前没有活跃的提醒。";
        }

        userTasks.sort(Comparator.comparing(
                task -> task.recurring() ? "" : String.valueOf(task.fireTimeEpochMs())));

        StringBuilder result = new StringBuilder("当前活跃的提醒：");
        for (int i = 0; i < userTasks.size(); i++) {
            ReminderTask task = userTasks.get(i);
            result.append('\n').append(i + 1).append(". ");
            result.append("ID=").append(task.id());
            if (task.recurring()) {
                result.append(" [周期] ").append(task.cronExpression());
            } else {
                result.append(" [单次] ")
                        .append(Instant.ofEpochMilli(task.fireTimeEpochMs())
                                .atZone(ZoneId.systemDefault())
                                .toLocalTime()
                                .toString());
            }
            result.append(" — ").append(task.message());
        }
        log.info("[ReminderService] listReminders 执行成功，userId={}", userId);
        return result.toString();
    }

    //调度与持久化

    /**
     * 持久化提醒任务并注册一次性定时调度。
     *
     * @param task 提醒任务
     * @param fireTime 触发时间
     * @param delayMinutes 延迟分钟数
     */
    private void persistAndSchedule(
            ReminderTask task, Instant fireTime, int delayMinutes) {
        String taskJson = gson.toJson(task);
        Duration ttl = Duration.ofMinutes(delayMinutes).plus(REMINDER_TTL_BUFFER);
        redisTemplate.opsForValue().set(reminderKey(task.id()), taskJson, ttl);
        log.info("提醒已存入Redis，reminderId={}，userId={}，fireTime={}",
                task.id(), task.userId(), fireTime);

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> fireReminder(task.id()), fireTime);
        scheduledFutures.put(task.id(), future);
        log.info("提醒定时任务已注册，reminderId={}，fireTime={}",
                task.id(), fireTime);
    }

    /**
     * 提醒触发时执行：查客户端、发微信消息。
     * 一次性提醒触发后自动清理，周期性提醒保留。
     *
     * @param reminderId 提醒唯一标识
     */
    private void fireReminder(String reminderId) {
        ReminderTask task = loadTask(reminderKey(reminderId));
        if (task == null) {
            log.warn("提醒任务不存在或已被清理，reminderId={}", reminderId);
            return;
        }

        Optional<ILinkClient> clientOptional = sessionRegistry
                .findClientByUserId(task.userId());
        if (clientOptional.isEmpty()) {
            log.warn("提醒触发时用户客户端不在线，reminderId={}，userId={}",
                    reminderId, task.userId());
            return;
        }

        String reminderMessage = "⏰ " + task.message();
        try {
            clientOptional.get().sendText(task.userId(), reminderMessage);
            log.info("提醒消息已发送，reminderId={}，userId={}，message={}",
                    reminderId, task.userId(), task.message());
        } catch (IOException | ILinkException exception) {
            log.error("提醒消息发送失败，reminderId={}，userId={}",
                    reminderId, task.userId(), exception);
        }

        if (!task.recurring()) {
            redisTemplate.delete(reminderKey(reminderId));
            scheduledFutures.remove(reminderId);
            log.info("一次性提醒已完成并清理，reminderId={}", reminderId);
        }
    }

    /**
     * 应用启动后重新加载Redis中未触发的提醒任务。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reloadOnStartup() {
        Set<String> keys;
        try {
            keys = redisTemplate.keys(REMINDER_KEY_PREFIX + "*");
        } catch (RuntimeException exception) {
            log.error("扫描Redis提醒任务失败，跳过启动恢复", exception);
            return;
        }
        if (keys == null || keys.isEmpty()) {
            log.debug("Redis中无待恢复的提醒任务");
            return;
        }

        int reloadedCount = 0;
        for (String key : keys) {
            ReminderTask task = loadTask(key);
            if (task == null) {
                continue;
            }

            if (task.recurring()) {
                CronTrigger trigger = new CronTrigger(
                        task.cronExpression(), ZoneId.systemDefault());
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> fireReminder(task.id()), trigger);
                scheduledFutures.put(task.id(), future);
                reloadedCount++;
                log.info("周期性提醒已恢复，reminderId={}，cron={}",
                        task.id(), task.cronExpression());
            } else {
                Instant fireTime = Instant.ofEpochMilli(
                        task.fireTimeEpochMs());
                if (fireTime.isBefore(Instant.now())) {
                    redisTemplate.delete(key);
                    log.info("已过期的提醒已删除，reminderId={}", task.id());
                    continue;
                }
                ScheduledFuture<?> future = taskScheduler.schedule(
                        () -> fireReminder(task.id()), fireTime);
                scheduledFutures.put(task.id(), future);
                reloadedCount++;
                log.info("一次性提醒已恢复，reminderId={}，fireTime={}",
                        task.id(), fireTime);
            }
        }
        log.info("提醒任务启动恢复完成，扫描数量={}，成功恢复={}",
                keys.size(), reloadedCount);
    }

    //内部工具方法

    /**
     * 校验userId和message公共参数。
     *
     * @param userId 微信用户ID
     * @param message 提醒内容
     * @throws IllegalArgumentException 参数无效时抛出
     */
    private void validateCommon(String userId, String message) {
        if (StringUtils.isBlank(userId)) {
            throw new IllegalArgumentException("userId不能为空");
        }
        if (StringUtils.isBlank(message)) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
    }

    /**
     * 从Redis加载提醒任务，读取或解析失败时返回null。
     *
     * @param key Redis Key
     * @return 提醒任务；不存在或解析失败返回null
     */
    private ReminderTask loadTask(String key) {
        try {
            String taskJson = redisTemplate.opsForValue().get(key);
            if (StringUtils.isBlank(taskJson)) {
                return null;
            }
            return gson.fromJson(taskJson, ReminderTask.class);
        } catch (RuntimeException exception) {
            log.error("提醒任务读取或解析失败，key={}", key, exception);
            return null;
        }
    }

    /**
     * 生成提醒任务的Redis Key。
     *
     * @param reminderId 提醒唯一标识
     * @return Redis Key
     */
    private String reminderKey(String reminderId) {
        return REMINDER_KEY_PREFIX + reminderId;
    }
}
