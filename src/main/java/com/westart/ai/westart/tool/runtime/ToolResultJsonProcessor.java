package com.westart.ai.westart.tool.runtime;

import dev.langchain4j.model.TokenCountEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具调用结果JSON处理器。
 *
 * 负责将合法JSON结果转换为紧凑字符串，有限解包仅包含result字段的
 * JSON包装，递归清理空节点、图片链接和数组中的重复元素，并对满足
 * 收益条件的对象数组提取公共字段。普通文本和非法JSON保持原样，避免
 * 处理失败影响工具调用链路。
 */
@Component
@RequiredArgsConstructor
public class ToolResultJsonProcessor {

    private static final String RESULT_FIELD_NAME = "result";
    private static final int MAX_UNWRAP_DEPTH = 2;
    private static final int COMMON_FIELD_MIN_ARRAY_SIZE = 6;
    private static final double MIN_TOKEN_REDUCTION_RATE = 0.05D;
    private static final String EMPTY_JSON_OBJECT = "{}";
    private static final String COMMON_FIELDS_NAME = "commonFields";
    private static final String ITEMS_NAME = "items";
    private static final Pattern IMAGE_EXTENSION_PATTERN = Pattern.compile(
            "(?i)\\.(?:png|jpe?g|gif|webp|bmp|svg)(?:[?#].*)?$");
    private static final Pattern EMBEDDED_IMAGE_URL_PATTERN = Pattern.compile(
            "(?i)https?://[^\\s\"'<>)]+\\.(?:png|jpe?g|gif|webp|bmp|svg)"
                    + "(?:\\?[^\\s\"'<>)]*)?");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile(
            "!\\[[^\\]]*]\\(\\s*[^)]*\\)",
            Pattern.DOTALL);
    private static final Set<String> IMAGE_FIELD_KEYWORDS = Set.of(
            "图片",
            "图像",
            "主图",
            "封面",
            "缩略图",
            "头像",
            "图标");

    private final ObjectMapper objectMapper;
    private final TokenCountEstimator tokenCountEstimator;

    /**
     * 处理工具调用返回的JSON字符串。
     *
     * @param rawResult 工具原始结果
     * @return 解包后的紧凑JSON；无法解析时返回原始结果
     */
    public String process(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return rawResult;
        }

        try {
            JsonNode resultNode = objectMapper.readTree(rawResult);
            resultNode = unwrapResult(resultNode);
            resultNode = cleanNode(resultNode, null);
            if (resultNode == null) {
                return EMPTY_JSON_OBJECT;
            }
            return objectMapper.writeValueAsString(resultNode);
        } catch (JacksonException exception) {
            return rawResult;
        }
    }

    /**
     * 递归清理节点并对数组元素进行去重。
     *
     * @param node 待清理节点
     * @param fieldName 当前字段名称，数组元素和根节点传入null
     * @return 清理后的节点；节点应被删除时返回null
     */
    private JsonNode cleanNode(
            JsonNode node,
            String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            return cleanObject((ObjectNode) node);
        }
        if (node.isArray()) {
            return cleanArray((ArrayNode) node, fieldName);
        }
        if (node.isString()) {
            return cleanString(node.asString(), fieldName);
        }
        return node;
    }

    /**
     * 清理对象中的无效字段。
     *
     * @param objectNode 待清理对象
     * @return 清理后的对象；对象为空时返回null
     */
    private JsonNode cleanObject(ObjectNode objectNode) {
        ObjectNode cleanedObject = objectMapper.createObjectNode();
        for (Map.Entry<String, JsonNode> property : objectNode.properties()) {
            JsonNode cleanedValue = cleanNode(
                    property.getValue(),
                    property.getKey());
            if (cleanedValue != null) {
                cleanedObject.set(property.getKey(), cleanedValue);
            }
        }
        return cleanedObject.isEmpty() ? null : cleanedObject;
    }

    /**
     * 清理数组中的无效元素，并按节点内容保留首次出现的元素。
     *
     * @param arrayNode 待清理数组
     * @param fieldName 数组字段名称
     * @return 清理、去重后的数组；数组为空时返回null
     */
    private JsonNode cleanArray(
            ArrayNode arrayNode,
            String fieldName) {
        ArrayNode cleanedArray = objectMapper.createArrayNode();
        Set<JsonNode> uniqueNodes = new LinkedHashSet<>();
        for (JsonNode element : arrayNode) {
            JsonNode cleanedElement = cleanNode(
                    element,
                    fieldName);
            if (cleanedElement != null && uniqueNodes.add(cleanedElement)) {
                cleanedArray.add(cleanedElement);
            }
        }
        if (cleanedArray.isEmpty()) {
            return null;
        }
        return extractCommonFields(cleanedArray);
    }

    /**
     * 对满足条件的对象数组提取公共字段。
     *
     * @param arrayNode 已完成清洗和去重的数组
     * @return Token减少比例超过阈值时返回压缩对象，否则返回原数组
     */
    private JsonNode extractCommonFields(ArrayNode arrayNode) {
        if (arrayNode.size() < COMMON_FIELD_MIN_ARRAY_SIZE
                || !containsOnlyObjects(arrayNode)) {
            return arrayNode;
        }

        ObjectNode commonFields = findCommonFields(arrayNode);
        if (commonFields.isEmpty()) {
            return arrayNode;
        }

        ArrayNode items = buildItems(arrayNode, commonFields);
        if (items == null) {
            return arrayNode;
        }

        ObjectNode compressedNode = objectMapper.createObjectNode();
        compressedNode.set(COMMON_FIELDS_NAME, commonFields);
        compressedNode.set(ITEMS_NAME, items);
        return hasEnoughTokenReduction(arrayNode, compressedNode)
                ? compressedNode
                : arrayNode;
    }

    /**
     * 判断数组是否全部由对象组成。
     *
     * @param arrayNode 待判断数组
     * @return 所有元素均为对象时返回true
     */
    private boolean containsOnlyObjects(ArrayNode arrayNode) {
        for (JsonNode element : arrayNode) {
            if (!element.isObject()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 查找所有对象中名称和值均完全相同的字段。
     *
     * @param arrayNode 对象数组
     * @return 公共字段对象
     */
    private ObjectNode findCommonFields(ArrayNode arrayNode) {
        ObjectNode commonFields = objectMapper.createObjectNode();
        ObjectNode firstItem = (ObjectNode) arrayNode.get(0);
        for (Map.Entry<String, JsonNode> property : firstItem.properties()) {
            if (isCommonProperty(arrayNode, property)) {
                commonFields.set(property.getKey(), property.getValue());
            }
        }
        return commonFields;
    }

    /**
     * 判断字段是否在数组的所有对象中完全相同。
     *
     * @param arrayNode 对象数组
     * @param property 候选公共字段
     * @return 所有对象均包含相同字段值时返回true
     */
    private boolean isCommonProperty(
            ArrayNode arrayNode,
            Map.Entry<String, JsonNode> property) {
        for (int index = 1; index < arrayNode.size(); index++) {
            JsonNode value = arrayNode.get(index).get(property.getKey());
            if (!property.getValue().equals(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建移除公共字段后的明细数组。
     *
     * @param arrayNode 原对象数组
     * @param commonFields 公共字段对象
     * @return 明细数组；存在空明细对象时返回null
     */
    private ArrayNode buildItems(
            ArrayNode arrayNode,
            ObjectNode commonFields) {
        ArrayNode items = objectMapper.createArrayNode();
        for (JsonNode element : arrayNode) {
            ObjectNode item = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> property
                    : (element).properties()) {
                if (commonFields.get(property.getKey()) == null) {
                    item.set(property.getKey(), property.getValue());
                }
            }
            if (item.isEmpty()) {
                return null;
            }
            items.add(item);
        }
        return items;
    }

    /**
     * 判断公共字段提取后的Token减少比例是否超过阈值。
     *
     * @param originalNode 原始数组
     * @param compressedNode 公共字段提取后的对象
     * @return Token减少比例严格超过阈值时返回true
     */
    private boolean hasEnoughTokenReduction(
            JsonNode originalNode,
            JsonNode compressedNode) {
        try {
            int originalTokens = tokenCountEstimator.estimateTokenCountInText(
                    objectMapper.writeValueAsString(originalNode));
            if (originalTokens <= 0) {
                return false;
            }
            int compressedTokens = tokenCountEstimator.estimateTokenCountInText(
                    objectMapper.writeValueAsString(compressedNode));
            double reductionRate = (double) (originalTokens - compressedTokens)
                    / originalTokens;
            return reductionRate > MIN_TOKEN_REDUCTION_RATE;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 清理字符串中的图片链接。
     *
     * @param value 原始字符串
     * @param fieldName 字段名称
     * @return 清理后的文本节点；字符串应被删除时返回null
     */
    private JsonNode cleanString(
            String value,
            String fieldName) {
        if (isImageUrl(value, fieldName)) {
            return null;
        }

        String cleanedValue = MARKDOWN_IMAGE_PATTERN.matcher(value)
                .replaceAll("");
        cleanedValue = EMBEDDED_IMAGE_URL_PATTERN.matcher(cleanedValue)
                .replaceAll("");
        if (cleanedValue.isBlank()) {
            return null;
        }
        return objectMapper.getNodeFactory().stringNode(cleanedValue);
    }

    /**
     * 判断字符串是否为图片链接。
     *
     * @param value 字符串值
     * @param fieldName 字段名称
     * @return 图片链接返回true，其他链接返回false
     */
    private boolean isImageUrl(String value, String fieldName) {
        String normalizedValue = value.trim();
        if (normalizedValue.regionMatches(true, 0, "data:image/", 0, 11)) {
            return true;
        }
        if (!isHttpUrl(normalizedValue)) {
            return false;
        }
        return isImageField(fieldName)
                || IMAGE_EXTENSION_PATTERN.matcher(normalizedValue).find();
    }

    /**
     * 判断字段名称是否包含图片语义关键词。
     *
     * @param fieldName 字段名称
     * @return 包含图片语义关键词时返回true
     */
    private boolean isImageField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String normalizedName = fieldName
                .replace("_", "")
                .replace("-", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        return IMAGE_FIELD_KEYWORDS.stream()
                .anyMatch(normalizedName::contains);
    }

    /**
     * 判断字符串是否为HTTP或HTTPS链接。
     *
     * @param value 字符串值
     * @return HTTP或HTTPS链接返回true
     */
    private boolean isHttpUrl(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    /**
     * 有限解包仅包含result字段且字段内容为JSON字符串的节点。
     *
     * @param rootNode 待处理的根节点
     * @return 解包后的节点
     */
    private JsonNode unwrapResult(JsonNode rootNode) {
        JsonNode currentNode = rootNode;
        for (int depth = 0; depth < MAX_UNWRAP_DEPTH; depth++) {
            if (!isResultWrapper(currentNode)) {
                break;
            }

            String wrappedResult = currentNode.get(RESULT_FIELD_NAME).asString();
            try {
                currentNode = objectMapper.readTree(wrappedResult);
            } catch (JacksonException exception) {
                break;
            }
        }
        return currentNode;
    }

    /**
     * 判断节点是否为不会丢失其他元数据的纯result包装。
     *
     * @param node 待判断节点
     * @return 仅包含文本类型result字段时返回true
     */
    private boolean isResultWrapper(JsonNode node) {
        if (!node.isObject() || node.size() != 1) {
            return false;
        }
        JsonNode resultNode = node.get(RESULT_FIELD_NAME);
        return resultNode != null && resultNode.isString();
    }
}
