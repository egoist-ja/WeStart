package com.westart.ai.westart.tool.entity;

import com.google.gson.annotations.SerializedName;
import com.westart.ai.westart.tool.domain.ToolType;

import java.util.Objects;

/**
 * 可被检索和执行的工具实体。
 *
 * @param id 工具唯一标识
 * @param type 工具类型
 * @param name 工具名称
 * @param description 工具功能描述，同时作为向量检索文本
 * @param inputSchema 工具入参JSON Schema
 * @param denseVector 工具描述对应的稠密向量
 */
public record ToolEntity(
        String id,
        ToolType type,
        String name,
        String description,
        String inputSchema,
        @SerializedName("description_dense_vector") float[] denseVector) {

    public ToolEntity(
            String id,
            ToolType type,
            String name,
            String description,
            String inputSchema) {
        this(id, type, name, description, inputSchema, null);
    }

    public ToolEntity {
        Objects.requireNonNull(id, "工具ID不能为空");
        Objects.requireNonNull(type, "工具类型不能为空");
        Objects.requireNonNull(name, "工具名称不能为空");
        Objects.requireNonNull(description, "工具描述不能为空");
        Objects.requireNonNull(inputSchema, "工具入参Schema不能为空");
        denseVector = denseVector == null ? null : denseVector.clone();
    }
}
