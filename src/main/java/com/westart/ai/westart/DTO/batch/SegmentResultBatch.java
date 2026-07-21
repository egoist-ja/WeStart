package com.westart.ai.westart.DTO.batch;

import com.westart.ai.westart.DTO.SegmentResult;
import dev.langchain4j.model.output.structured.Description;

import java.util.List;

@Description("用户当前消息批次的有序语义路由结果")
public record SegmentResultBatch(
        @Description("按照用户原始表达顺序排列的语义片段；不得遗漏明确任务")
        List<SegmentResult> segmentResults
) {
}
