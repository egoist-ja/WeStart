package com.westart.ai.westart.service.domain;

import dev.langchain4j.model.output.structured.Description;

/**
 * 枚举，表示消息的路由类型
 */
public enum RouteType {

    @Description("正常聊天、知识问答、文本处理以及对用户已发送图片的理解分析，不包含生成图片或生成视频")
    CHAT,

    @Description("用户要求生成、绘制、设计或编辑图片")
    IMAGE,

    @Description("用户要求生成、制作或编辑视频")
    VIDEO
}
