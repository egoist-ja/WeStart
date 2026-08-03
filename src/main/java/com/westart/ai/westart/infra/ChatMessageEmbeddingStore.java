package com.westart.ai.westart.infra;

import com.westart.ai.westart.entity.UserTopicMemory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageEmbeddingStore implements EmbeddingStore<UserTopicMemory> {

    @Override
    public String add(Embedding embedding) {
        return "";
    }

    @Override
    public void add(String id, Embedding embedding) {

    }

    @Override
    public String add(Embedding embedding, UserTopicMemory userTopicMemory) {
        return "";
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return List.of();
    }

    @Override
    public EmbeddingSearchResult<UserTopicMemory> search(EmbeddingSearchRequest request) {
        return null;
    }
}
