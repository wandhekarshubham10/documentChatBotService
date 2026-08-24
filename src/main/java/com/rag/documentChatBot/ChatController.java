package com.rag.documentChatBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.ServerResponse;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final UserDocumentRepository documents;
    private final AiServiceFacade ai;
    private final UpstashVectorService vector;
    private final ConversationService conversations;
    private final RagSupport support;
    private final int topK;
    private final int rateLimit;

    public ChatController(UserDocumentRepository documents, AiServiceFacade ai, UpstashVectorService vector,
            ConversationService conversations, RagSupport support, @Value("${app.rag.top-k:4}") int topK,
            @Value("${app.rate-limit.per-minute:60}") int rateLimit) {
        this.documents = documents; this.ai = ai; this.vector = vector; this.conversations = conversations;
        this.support = support; this.topK = topK; this.rateLimit = rateLimit;
    }

    @PostMapping(value = "/completions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> complete(@RequestBody Map<String, String> request,
            @AuthenticationPrincipal OAuth2User user, @RequestHeader(value = "X-Forwarded-For", required = false) String forwarded) {
        String query = request.getOrDefault("query", "").trim();
        String documentId = request.getOrDefault("documentId", "").trim();
        String ownerId = user.getAttribute("sub");
        String ip = forwarded == null ? "unknown" : forwarded.split(",")[0].trim();
        if (query.isBlank() || documentId.isBlank()) return ResponseEntity.badRequest().body(Flux.just("Please select a document and enter a question."));
        if (!support.allow(ownerId, ip, rateLimit)) return ResponseEntity.status(429).body(Flux.just("Rate limit exceeded. Please try again shortly."));
        if (documents.findByIdAndOwnerId(documentId, ownerId).filter(UserDocument::isEmbeddingReady).isEmpty()) {
            return ResponseEntity.status(409).body(Flux.just("This PDF is still being indexed. Please try again in a moment."));
        }

        String cacheKey = support.cacheKey(ownerId, documentId, query);
        String cached = support.cached(cacheKey);
        if (cached != null) {
            System.out.println("Chat response (cached): " + cached);
            return ResponseEntity.ok(Flux.fromArray(cached.split("(?<=\\s)")));
        }

        return ResponseEntity.ok(ai.embed(query)
            .flatMap(embedding -> vector.query(embedding, ownerId, documentId, topK))
            .flatMapMany(matches -> generate(ownerId, documentId, query, matches, cacheKey)));
    }

    private Flux<String> generate(String ownerId, String documentId, String query,
            List<Map<String, Object>> matches, String cacheKey) {
        List<String> sourceIds = new ArrayList<>();
        StringBuilder context = new StringBuilder();
        for (Map<String, Object> match : matches) {
            sourceIds.add(String.valueOf(match.get("id")));
            Object metadataObject = match.get("metadata");
            if (metadataObject instanceof Map<?, ?> metadata) context.append(metadata.get("content")).append("\n\n");
        }
        String system = "You answer questions only from the supplied PDF context. If the answer is not explicitly supported, say: I could not find that in this document. Never invent facts, citations, or details. Keep answers concise and cite the relevant context in plain language.";
        String prompt = "PDF CONTEXT:\n" + context + "\nQUESTION:\n" + query;
        return ai.streamChat(system, prompt).collectList().flatMapMany(tokens -> {
            String answer = String.join("", tokens);
            System.out.println("Chat response: " + (answer.isBlank() ? "[blank - no content tokens received]" : answer));
            support.cache(cacheKey, answer);
            conversations.saveTurn(ownerId, documentId, query, answer, sourceIds);
            return Flux.fromIterable(tokens);
        });
    }

}
