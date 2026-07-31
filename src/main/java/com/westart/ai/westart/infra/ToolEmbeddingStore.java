package com.westart.ai.westart.infra;


import com.westart.ai.westart.entity.ToolEntity;
import com.westart.ai.westart.entity.ToolType;
import com.westart.ai.westart.repository.ToolRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ToolEmbeddingStore implements EmbeddingStore<ToolEntity> {

    private final ToolRepository toolRepository;

    @Override
    public String add(Embedding embedding) {
        return "";
    }

    @Override
    public void add(String id, Embedding embedding) {

    }

    @Override
    public String add(Embedding embedding, ToolEntity toolEntity) {
        return "";
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return List.of();
    }

    @Override
    public EmbeddingSearchResult<ToolEntity> search(EmbeddingSearchRequest request) {
        SearchResp response = toolRepository.search(
                request.query(),
                request.maxResults(),
                request.queryEmbedding().vector());
        List<EmbeddingMatch<ToolEntity>> matches =
                response.getSearchResults().stream()
                        .flatMap(List::stream)
                        .filter(result ->
                                result.getScore() >= request.minScore())
                        .map(this::toEmbeddingMatch)
                        .toList();
        return new EmbeddingSearchResult<>(matches);
    }

    /**
     * 将混合搜索的结果转换为EmbeddingMatch<ToolEntity>对象
     * @param result
     * @return
     */
    private EmbeddingMatch<ToolEntity> toEmbeddingMatch(SearchResp.SearchResult result) {

        Map<String, Object> fields = result.getEntity();

        ToolEntity tool = new ToolEntity(
                result.getPrimaryKey(),
                ToolType.valueOf(String.valueOf(fields.get("type"))),
                String.valueOf(fields.get("name")),
                String.valueOf(fields.get("description")),
                String.valueOf(fields.get("inputSchema"))
        );

        return new EmbeddingMatch<>(
                result.getScore().doubleValue(),
                result.getPrimaryKey(),
                null,
                tool
        );
    }
}
