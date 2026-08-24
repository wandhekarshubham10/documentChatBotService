package com.rag.documentChatBot;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("user_documents")
public class UserDocument {
    @Id
    private String id;
    private String ownerId;
    private String fileName;
    private String contentType;
    private long size;
    private byte[] content;
    private Instant uploadedAt;
    private boolean embeddingReady;
    private String embeddingStatus;
    private int embeddingProgress;
    private int processedChunks;
    private int totalChunks;
    private String embeddingError;

    protected UserDocument() {
    }

    public UserDocument(String ownerId, String fileName, String contentType, long size, byte[] content) {
        this.ownerId = ownerId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.content = content;
        this.uploadedAt = Instant.now();
        this.embeddingReady = false;
        this.embeddingStatus = "QUEUED";
        this.embeddingProgress = 0;
        this.processedChunks = 0;
        this.totalChunks = 0;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSize() { return size; }
    public byte[] getContent() { return content; }
    public Instant getUploadedAt() { return uploadedAt; }
    public boolean isEmbeddingReady() { return embeddingReady; }
    public void setEmbeddingReady(boolean embeddingReady) { this.embeddingReady = embeddingReady; }
    public String getEmbeddingStatus() { return embeddingStatus; }
    public int getEmbeddingProgress() { return embeddingProgress; }
    public int getProcessedChunks() { return processedChunks; }
    public int getTotalChunks() { return totalChunks; }
    public String getEmbeddingError() { return embeddingError; }
    public void setEmbeddingStatus(String embeddingStatus) { this.embeddingStatus = embeddingStatus; }
    public void setEmbeddingProgress(int embeddingProgress) { this.embeddingProgress = embeddingProgress; }
    public void setProcessedChunks(int processedChunks) { this.processedChunks = processedChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
    public void setEmbeddingError(String embeddingError) { this.embeddingError = embeddingError; }
}
