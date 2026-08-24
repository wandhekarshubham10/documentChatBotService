package com.rag.documentChatBot;

import java.util.List;
import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserDocumentRepository extends MongoRepository<UserDocument, String> {
    List<UserDocument> findByOwnerIdOrderByUploadedAtDesc(String ownerId);
    Optional<UserDocument> findByIdAndOwnerId(String id, String ownerId);
}
