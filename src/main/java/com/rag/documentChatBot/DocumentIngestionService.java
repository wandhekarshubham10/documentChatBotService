package com.rag.documentChatBot;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class DocumentIngestionService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentIngestionService.class);
    private final UserDocumentRepository documents;
    private final AiServiceFacade ai;
    private final UpstashVectorService vector;
    private final WebClient markitdownClient;
    private final String markitdownUrl;
    private final Duration markitdownTimeout;
    private final int chunkSize;
    private final int overlap;

    public DocumentIngestionService(UserDocumentRepository documents, AiServiceFacade ai,
            UpstashVectorService vector, @Value("${app.rag.chunk-size:800}") int chunkSize,
            @Value("${app.rag.chunk-overlap:150}") int overlap,
            WebClient.Builder webClientBuilder,
            @Value("${app.markitdown.url:https://markitdown-pdf-3.onrender.com}") String markitdownUrl,
            @Value("${app.markitdown.timeout-seconds:120}") long markitdownTimeoutSeconds) {
        this.documents = documents;
        this.ai = ai;
        this.vector = vector;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.markitdownClient = webClientBuilder.build();
        this.markitdownUrl = markitdownUrl;
        this.markitdownTimeout = Duration.ofSeconds(markitdownTimeoutSeconds);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupMarkitdown() {
        markitdownClient.get()
                .uri(markitdownUrl + "/")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .subscribe(
                        ignored -> logger.info("MarkItDown service warmed up"),
                        error -> logger.warn("MarkItDown warm-up failed: {}", error.getMessage()));
    }

    @Async
    public void index(String documentId, String ownerId, byte[] pdf) {
        markProcessing(documentId, ownerId);
        try {
            String text = extractWithMarkitdown(pdf);
            List<String> chunks = new ArrayList<>();
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + chunkSize);
                chunks.add(text.substring(start, end));
                if (end == text.length()) break;
                start = Math.max(end - overlap, start + 1);
            }
            updateChunkTotals(documentId, ownerId, chunks.size());
                List<Map<String, Object>> vectors = new ArrayList<>();
            for (int chunk = 0; chunk < chunks.size(); chunk++) {
                String content = chunks.get(chunk);
                String chunkId = documentId + "-" + chunk;
                List<Double> embedding = ai.embed(content).block();
                vectors.add(Map.of("id", chunkId, "vector", embedding,
                    "metadata", Map.of("ownerId", ownerId, "documentId", documentId, "content", content)));
                int processed = chunk + 1;
                documents.findByIdAndOwnerId(documentId, ownerId).ifPresent(saved -> {
                    saved.setProcessedChunks(processed);
                    saved.setEmbeddingProgress(chunks.isEmpty() ? 100 : (processed * 100 / chunks.size()));
                    documents.save(saved);
                });
            }
            vector.upsert(vectors).block();
            documents.findByIdAndOwnerId(documentId, ownerId).ifPresent(saved -> {
                saved.setEmbeddingReady(true);
                saved.setEmbeddingStatus("READY");
                saved.setEmbeddingProgress(100);
                documents.save(saved);
            });
        } catch (Exception exception) {
            logger.error("Embedding failed for document {} owned by {}", documentId, ownerId, exception);
            documents.findByIdAndOwnerId(documentId, ownerId).ifPresent(saved -> {
                saved.setEmbeddingStatus("FAILED");
                saved.setEmbeddingError("Embedding preparation failed. Please upload the PDF again.");
                documents.save(saved);
            });
        }
    }

    private String extractWithMarkitdown(byte[] pdf) {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "document.pdf";
            }
        }).contentType(MediaType.APPLICATION_PDF);

        try {
            Map<?, ?> response = markitdownClient.post()
                    .uri(markitdownUrl + "/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(body.build()))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(markitdownTimeout)
                    .block();
            Object markdown = response == null ? null : response.get("markdown");
            if (markdown instanceof String text && !text.isBlank()) return text.trim();
            throw new IllegalStateException("MarkItDown returned no markdown text");
        } catch (RuntimeException exception) {
            logger.warn("MarkItDown conversion failed, using PDFBox fallback: {}", exception.getMessage());
            return extractWithPdfBox(pdf);
        }
    }

    private String extractWithPdfBox(byte[] pdf) {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document).replaceAll("\\s+", " ").trim();
        } catch (Exception exception) {
            throw new IllegalStateException("PDF text extraction failed", exception);
        }
    }

    public void deleteEmbeddings(String documentId, int totalChunks) {
        if (totalChunks <= 0) return;
        vector.delete(IntStream.range(0, totalChunks)
            .mapToObj(chunk -> documentId + "-" + chunk).toList()).block();
    }

    private void markProcessing(String documentId, String ownerId) {
        documents.findByIdAndOwnerId(documentId, ownerId).ifPresent(saved -> {
            saved.setEmbeddingReady(false);
            saved.setEmbeddingStatus("PROCESSING");
            saved.setProcessedChunks(0);
            saved.setEmbeddingProgress(0);
            saved.setEmbeddingError(null);
            documents.save(saved);
        });
    }

    private void updateChunkTotals(String documentId, String ownerId, int totalChunks) {
        documents.findByIdAndOwnerId(documentId, ownerId).ifPresent(saved -> {
            saved.setTotalChunks(totalChunks);
            documents.save(saved);
        });
    }
}
