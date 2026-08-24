package com.rag.documentChatBot;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

@Service
public class UpstashVectorService {
    private static final Logger logger = LoggerFactory.getLogger(UpstashVectorService.class);
    private final WebClient client;
    private final String token;

    public UpstashVectorService(WebClient.Builder builder,
            @Value("${upstash.vector.url:}") String url,
            @Value("${upstash.vector.token:}") String token) {
        this.client = builder.baseUrl(url).build();
        this.token = token;
    }

    public Mono<Void> upsert(String id, List<Double> vector, Map<String, Object> metadata) {
        return upsert(List.of(Map.of("id", id, "vector", vector, "metadata", metadata)));
    }

    public Mono<Void> upsert(List<Map<String, Object>> vectors) {
        if (vectors == null || vectors.isEmpty()) return Mono.empty();
        
        logger.debug("Upserting {} vectors", vectors.size());

        // Send 'vectors' directly as the JSON body (Array of Objects)
        return client.post().uri("/upsert")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(vectors)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("<empty response body>")
                        .map(body -> {
                            logger.error("Upstash Vector upsert failed with HTTP {}: {}", response.statusCode(), body);
                            return new IllegalStateException("Upstash Vector upsert failed: " + body);
                        }))
                .bodyToMono(Void.class);
    }

    public Mono<List<Map<String, Object>>> query(List<Double> vector, String ownerId, String documentId, int topK) {
        String filter = "ownerId = '" + ownerId.replace("'", "\\'") + "' AND documentId = '" + documentId.replace("'", "\\'") + "'";
        return client.post().uri("/query")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("vector", vector, "topK", topK, "includeMetadata", true, "filter", filter))
                .retrieve().bodyToMono(Map.class)
                .map(body -> {
                    Object result = body.get("result");
                    if (result instanceof List<?> list) {
                        return (List<Map<String, Object>>) (List<?>) list;
                    }
                    return List.of();
                });
    }

    public Mono<Void> delete(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Mono.empty();
        
        return client.post().uri("/delete")
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("ids", ids))
                .retrieve()
                .bodyToMono(Void.class);
    }
}