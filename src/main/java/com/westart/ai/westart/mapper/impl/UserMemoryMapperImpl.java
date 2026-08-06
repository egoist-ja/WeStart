package com.westart.ai.westart.mapper.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.westart.ai.westart.entity.UserMemory;
import com.westart.ai.westart.mapper.UserMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

/**
 * 用户长期记忆数据访问，使用LambdaWrapper构建SQL。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class UserMemoryMapperImpl {

    private final UserMemoryMapper userMemoryMapper;

    /**
     * 查询指定微信用户唯一的长期画像。
     *
     * <p>每个微信用户只保存一条完整画像。</p>
     */
    public List<UserMemory> selectByWechatUserId(String wechatUserId) {
        if (wechatUserId == null || wechatUserId.isBlank()) {
            return List.of();
        }

        LambdaQueryWrapper<UserMemory> wrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getWechatUserId, wechatUserId);
        UserMemory userMemory = userMemoryMapper.selectOne(wrapper);
        return userMemory == null ? List.of() : List.of(userMemory);
    }

    /**
     * 新增或更新长期记忆。
     *
     * <p>按wechatUserId查询，存在则更新画像和来源并自增版本，
     * 不存在则新增，版本设为1。</p>
     */
    public int upsertUserMemory(UserMemory memory) {
        if (memory == null) {
            return 0;
        }

        LambdaQueryWrapper<UserMemory> queryWrapper = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getWechatUserId, memory.getWechatUserId());
        UserMemory existing = userMemoryMapper.selectOne(queryWrapper);

        if (existing != null) {
            if (Objects.equals(existing.getProfileContent(), memory.getProfileContent())
                    && Objects.equals(
                            existing.getLatestSourceMessageId(),
                            memory.getLatestSourceMessageId())) {
                return 0;
            }
            LambdaUpdateWrapper<UserMemory> updateWrapper = new LambdaUpdateWrapper<UserMemory>()
                    .eq(UserMemory::getWechatUserId, memory.getWechatUserId())
                    .set(UserMemory::getProfileContent, memory.getProfileContent())
                    .set(
                            UserMemory::getLatestSourceMessageId,
                            memory.getLatestSourceMessageId())
                    .setSql("profile_version = profile_version + 1");
            return userMemoryMapper.update(null, updateWrapper);
        }

        memory.setProfileVersion(1);
        return userMemoryMapper.insert(memory);
    }

}
