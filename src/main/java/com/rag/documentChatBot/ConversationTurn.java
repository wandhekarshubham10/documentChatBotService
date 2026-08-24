package com.rag.documentChatBot;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("conversation_turns")
public class ConversationTurn {
    @Id private String id;
    private String ownerId;
    private String documentId;
    private String query;
    private String response;
    private List<String> sourceChunkIds;
    private Instant createdAt = Instant.now();

    protected ConversationTurn() { }
    public ConversationTurn(String ownerId, String documentId, String query, String response, List<String> sourceChunkIds) {
        this.ownerId = ownerId; this.documentId = documentId; this.query = query;
        this.response = response; this.sourceChunkIds = sourceChunkIds;
    }
}
