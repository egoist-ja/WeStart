package com.westart.ai.westart.DTO;

import com.westart.ai.westart.service.domain.RouteType;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

@Description("用户连续消息经过语义分块后形成的单个路由片段")
public record SegmentResult(
        @Description("语义片段的路由类型，只能是CHAT、IMAGE或VIDEO")
        RouteType type,
        @Description("按原始顺序提取的核心请求列表；合并语义连续的消息，删除重复表达，但不得添加用户未表达的要求,要记录消息在消息列表中的顺序")
        List<Integer> content,
        @Description("生成任务可直接使用的完整描述；仅从相关上下文补全指代和省略信息，CHAT类型固定返回空字符串")
        String context,
        @Description("当前请求是否具备执行所需的核心信息；CHAT固定为true，生成任务不得将可选的风格、尺寸等参数缺失判定为不可执行")
        boolean executable,
        @Description("请求不可执行时直接发送给用户的补充信息提示；可执行时固定返回空字符串")
        String clarification
) {
}
