package com.westart.ai.westart.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.westart.ai.westart.entity.UserMemory;
import com.westart.ai.westart.mapper.UserMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户长期记忆数据访问，使用LambdaWrapper构建SQL。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserMemoryMapperImpl {

    private final UserMemoryMapper userMemoryMapper;

    /**
     * 查询指定微信用户的全部长期记忆，按更新时间倒序。
     */
    public List<UserMemory> selectByWechatUserId(String wechatUserId) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            return List.of();
        }

        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getWechatUserId, wechatUserId)
                .orderByDesc(UserMemory::getUpdatedAt);
        return userMemoryMapper.selectList(wrapper);
    }

    /**
     * 新增或更新长期记忆。
     *
     * <p>按wechatUserId + memoryKey查询，存在则更新内容和来源并自增version，
     * 不存在则新增，version设为1。</p>
     */
    public int upsertUserMemory(UserMemory memory) {
        if (memory == null) {
            return 0;
        }

        LambdaQueryWrapper<UserMemory> queryWrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getWechatUserId, memory.getWechatUserId())
                .eq(UserMemory::getMemoryKey, memory.getMemoryKey());
        UserMemory existing = userMemoryMapper.selectOne(queryWrapper);

        if (existing != null) {
            LambdaUpdateWrapper<UserMemory> updateWrapper = new LambdaUpdateWrapper<UserMemory>()
                    .eq(UserMemory::getWechatUserId, memory.getWechatUserId())
                    .eq(UserMemory::getMemoryKey, memory.getMemoryKey())
                    .set(UserMemory::getContent, memory.getContent())
                    .set(UserMemory::getSourceMessageId, memory.getSourceMessageId())
                    .setSql("version = version + 1");
            return userMemoryMapper.update(null, updateWrapper);
        }

        memory.setVersion(1);
        return userMemoryMapper.insert(memory);
    }

    /**
     * 删除指定微信用户的一条长期记忆。
     */
    public int deleteByWechatUserIdAndMemoryKey(String wechatUserId, String memoryKey) {
        if (wechatUserId == null || wechatUserId.isBlank()
                || memoryKey == null || memoryKey.isBlank()) {
            return 0;
        }

        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getWechatUserId, wechatUserId)
                .eq(UserMemory::getMemoryKey, memoryKey);
        return userMemoryMapper.delete(wrapper);
    }
}
