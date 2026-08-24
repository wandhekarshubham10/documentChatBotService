package com.rag.documentChatBot;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConversationTurnRepository extends MongoRepository<ConversationTurn, String> { }
