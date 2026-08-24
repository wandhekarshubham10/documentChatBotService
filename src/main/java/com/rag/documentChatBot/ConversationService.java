package com.rag.documentChatBot;

import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {
    private final ConversationTurnRepository turns;

    public ConversationService(ConversationTurnRepository turns) {
        this.turns = turns;
    }

    @Async
    public void saveTurn(String ownerId, String documentId, String query, String response, List<String> sourceIds) {
        turns.save(new ConversationTurn(ownerId, documentId, query, response, sourceIds));
    }
}
