package com.westart.ai.westart.service.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具检索描述规范化模块。
 *
 * 第三方MCP工具描述可能混入参数手册、目录、示例和流程说明，既可能
 * 超过Milvus的VarChar字节限制，也会给向量检索引入无关语义。本模块
 * 统一提取能力摘要、有限的使用场景和关键参数名称，生成长度受控的
 * 检索描述，同时保持原始工具定义不变，避免影响运行时工具调用。
 */
@Slf4j
@Component
public class ToolDescriptionNormalizer {

    private static final int MAX_DESCRIPTION_BYTES = 1600;
    private static final int MAX_SUMMARY_SENTENCES = 3;
    private static final int MAX_USAGE_SCENARIOS = 3;
    private static final int MAX_PARAMETER_NAMES = 8;
    private static final String SECTION_SEPARATOR = " ";
    private static final Pattern SENTENCE_SEPARATOR =
            Pattern.compile("(?<=[。！？.!?])\\s*");
    private static final Pattern LIST_MARKER =
            Pattern.compile("^[-*•]\\s*");
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+");
    private static final Pattern DESCRIPTION_HEADING =
            Pattern.compile("^description\\s*[:：]\\s*(.*)$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern WHEN_HEADING =
            Pattern.compile("^(when|适用场景)\\s*[:：]\\s*$",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SECTION_HEADING = Pattern.compile(
            "^(description|when|适用场景|input|inputs|parameters?|"
                    + "output|outputs|next|form menu|exact per-form data shapes|"
                    + "escape modes|examples?|rules?|notes?|preconditions?|"
                    + "conditions?|requirements?|constraints?|前置条件|"
                    + "调用条件)\\s*[:：].*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * 生成用于工具向量检索的精简描述。
     *
     * @param specification 工具定义
     * @return UTF-8字节数不超过预算的检索描述
     * @throws IllegalArgumentException 工具定义或工具名称无效时抛出
     */
    public String normalize(ToolSpecification specification) {
        if (specification == null) {
            throw new IllegalArgumentException("工具定义不能为空");
        }
        String toolName = specification.name();
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }

        String rawDescription = specification.description();
        if (rawDescription == null || rawDescription.isBlank()) {
            return truncateToBudget(toolName);
        }

        String[] lines = normalizeLineBreaks(rawDescription).split("\\n");
        List<String> descriptionParts = new ArrayList<>();
        extractSummary(lines).forEach(part ->
                appendWithinBudget(descriptionParts, part));

        List<String> usageScenarios = extractUsageScenarios(lines);
        if (!usageScenarios.isEmpty()) {
            appendWithinBudget(
                    descriptionParts,
                    "适用场景：" + String.join("、", usageScenarios));
        }

        List<String> parameterNames = extractParameterNames(
                specification.parameters());
        if (!parameterNames.isEmpty()) {
            appendWithinBudget(
                    descriptionParts,
                    "关键参数：" + String.join("、", parameterNames));
        }

        if (descriptionParts.isEmpty()) {
            return truncateToBudget(toolName);
        }
        return String.join(SECTION_SEPARATOR, descriptionParts);
    }

    /**
     * 提取原始描述开头最多三句能力摘要。
     */
    private List<String> extractSummary(String[] lines) {
        StringBuilder summary = new StringBuilder();
        boolean summaryStarted = false;
        for (String line : lines) {
            String normalizedLine = normalizeText(line);
            if (normalizedLine.isEmpty()) {
                if (summaryStarted) {
                    break;
                }
                continue;
            }
            Matcher descriptionMatcher = DESCRIPTION_HEADING.matcher(
                    normalizedLine);
            if (descriptionMatcher.matches()) {
                appendText(summary, descriptionMatcher.group(1));
                summaryStarted = true;
                continue;
            }
            if (SECTION_HEADING.matcher(normalizedLine).matches()) {
                break;
            }
            appendText(summary, normalizedLine);
            summaryStarted = true;
        }

        if (summary.isEmpty()) {
            return List.of();
        }
        String[] sentences = SENTENCE_SEPARATOR.split(summary.toString());
        List<String> result = new ArrayList<>(MAX_SUMMARY_SENTENCES);
        for (String sentence : sentences) {
            String normalizedSentence = normalizeText(sentence);
            if (!normalizedSentence.isEmpty()) {
                result.add(normalizedSentence);
            }
            if (result.size() == MAX_SUMMARY_SENTENCES) {
                break;
            }
        }
        return result;
    }

    /**
     * 提取描述中的有限使用场景。
     */
    private List<String> extractUsageScenarios(String[] lines) {
        Set<String> scenarios = new LinkedHashSet<>();
        boolean collecting = false;
        for (String line : lines) {
            String normalizedLine = normalizeText(line);
            if (!collecting) {
                collecting = WHEN_HEADING.matcher(normalizedLine).matches();
                continue;
            }
            if (normalizedLine.isEmpty()) {
                continue;
            }
            if (SECTION_HEADING.matcher(normalizedLine).matches()) {
                break;
            }

            String scenario = normalizeText(
                    LIST_MARKER.matcher(normalizedLine).replaceFirst(""));
            if (!scenario.isEmpty()) {
                scenarios.add(scenario);
            }
            if (scenarios.size() == MAX_USAGE_SCENARIOS) {
                break;
            }
        }
        return List.copyOf(scenarios);
    }

    /**
     * 提取有限数量的顶层参数名称。
     */
    private List<String> extractParameterNames(JsonObjectSchema parameters) {
        if (parameters == null || parameters.properties() == null) {
            return List.of();
        }

        Set<String> parameterNames = new LinkedHashSet<>();
        if (parameters.required() != null) {
            parameters.required().stream()
                    .filter(parameters.properties()::containsKey)
                    .forEach(parameterNames::add);
        }
        parameterNames.addAll(parameters.properties().keySet());
        return parameterNames.stream()
                .limit(MAX_PARAMETER_NAMES)
                .toList();
    }

    /**
     * 在UTF-8字节预算内追加完整描述片段。
     */
    private void appendWithinBudget(List<String> parts, String part) {
        String normalizedPart = normalizeText(part);
        if (normalizedPart.isEmpty()) {
            return;
        }

        String candidate = parts.isEmpty()
                ? normalizedPart
                : String.join(SECTION_SEPARATOR, parts)
                        + SECTION_SEPARATOR
                        + normalizedPart;
        if (utf8Length(candidate) <= MAX_DESCRIPTION_BYTES) {
            parts.add(normalizedPart);
            return;
        }
        if (parts.isEmpty()) {
            parts.add(truncateToBudget(normalizedPart));
        }
    }

    /**
     * 按Unicode码点进行UTF-8安全裁剪。
     */
    private String truncateToBudget(String value) {
        String normalizedValue = normalizeText(value);
        if (utf8Length(normalizedValue) <= MAX_DESCRIPTION_BYTES) {
            return normalizedValue;
        }

        String ellipsis = "…";
        int byteBudget = MAX_DESCRIPTION_BYTES - utf8Length(ellipsis);
        int currentBytes = 0;
        int endIndex = 0;
        while (endIndex < normalizedValue.length()) {
            int codePoint = normalizedValue.codePointAt(endIndex);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = utf8Length(character);
            if (currentBytes + characterBytes > byteBudget) {
                break;
            }
            currentBytes += characterBytes;
            endIndex += Character.charCount(codePoint);
        }
        return normalizedValue.substring(0, endIndex).strip() + ellipsis;
    }

    private void appendText(StringBuilder target, String value) {
        String normalizedValue = normalizeText(value);
        if (normalizedValue.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
        }
        target.append(normalizedValue);
    }

    private String normalizeLineBreaks(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private String normalizeText(String value) {
        return value == null
                ? ""
                : WHITESPACE.matcher(value.replace("`", ""))
                        .replaceAll(" ")
                        .trim();
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
