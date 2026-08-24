package com.rag.documentChatBot;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class AiServiceFacade {
    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String chatModel;

    public AiServiceFacade(WebClient.Builder builder,
            @Value("${app.mistral.base-url:https://api.mistral.ai}") String baseUrl,
            @Value("${spring.ai.mistralai.api-key:}") String apiKey,
            @Value("${spring.ai.mistralai.chat.options.model:mistral-large-latest}") String chatModel) {
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.chatModel = chatModel;
    }

    public Mono<List<Double>> embed(String text) {
        return client.post().uri("/v1/embeddings")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "mistral-embed", "input", List.of(text)))
                .retrieve()
                .bodyToMono(Map.class)
                .map(body -> {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                    return (List<Double>) data.get(0).get("embedding");
                });
    }

    public Flux<String> streamChat(String systemPrompt, String userPrompt) {
        ParameterizedTypeReference<ServerSentEvent<String>> typeRef =
                new ParameterizedTypeReference<>() {};

        return client.post().uri("/v1/chat/completions")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", chatModel,
                        "stream", true,
                        "temperature", 0.2,
                        "messages", List.of(
                                Map.of("role", "system", "content", systemPrompt),
                                Map.of("role", "user", "content", userPrompt)
                        )
                ))
                .retrieve()
                .bodyToFlux(typeRef)
                .mapNotNull(ServerSentEvent::data)
                .filter(data -> !data.isBlank() && !"[DONE]".equals(data.trim()))
                .map(this::extractContent)
                .filter(content -> !content.isEmpty());
    }

    private String extractContent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode delta = root.path("choices").path(0).path("delta");
            
            // Mistral occasionally sends delta without "content" (e.g. role headers or finish reasons)
            if (delta.has("content") && !delta.path("content").isNull()) {
                return delta.path("content").asText();
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}