package com.rag.documentChatBot;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class UserDocumentController {
    private static final long MAX_FILE_SIZE = 3 * 1024 * 1024;

    private final UserDocumentRepository repository;
    private final DocumentIngestionService ingestion;

    public UserDocumentController(UserDocumentRepository repository, DocumentIngestionService ingestion) {
        this.repository = repository;
        this.ingestion = ingestion;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal OAuth2User user) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a PDF file to upload."));
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "PDF files must be 3 MB or smaller."));
        }
        if (!isPdf(file)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only PDF files are supported."));
        }

        try {
                String ownerId = user.getAttribute("sub");
                UserDocument document = repository.save(new UserDocument(
                    ownerId,
                    file.getOriginalFilename() == null ? "document.pdf" : file.getOriginalFilename(),
                    MediaType.APPLICATION_PDF_VALUE,
                    file.getSize(),
                    file.getBytes()));
            ingestion.index(document.getId(), ownerId, document.getContent());
            return ResponseEntity.status(HttpStatus.CREATED).body(toMetadata(document));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of("error", "The PDF could not be stored."));
        }
    }

    @GetMapping
    List<Map<String, Object>> list(@AuthenticationPrincipal OAuth2User user) {
        return repository.findByOwnerIdOrderByUploadedAtDesc(user.getAttribute("sub")).stream()
                .map(this::toMetadata)
                .toList();
    }

    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable String id, @AuthenticationPrincipal OAuth2User user) {
        return repository.findByIdAndOwnerId(id, user.getAttribute("sub"))
                .map(document -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(document.getFileName()).build().toString())
                        .body(document.getContent()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    ResponseEntity<?> retry(@PathVariable String id, @AuthenticationPrincipal OAuth2User user) {
        return repository.findByIdAndOwnerId(id, user.getAttribute("sub"))
                .map(document -> {
                    try {
                        ingestion.deleteEmbeddings(id, document.getTotalChunks());
                    } catch (RuntimeException exception) {
                        return ResponseEntity.internalServerError().body(Map.of("error", "Old embeddings could not be cleared."));
                    }
                    ingestion.index(id, document.getOwnerId(), document.getContent());
                    return ResponseEntity.accepted().body(toMetadata(document));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable String id, @AuthenticationPrincipal OAuth2User user) {
        return repository.findByIdAndOwnerId(id, user.getAttribute("sub"))
                .map(document -> {
                    try {
                        ingestion.deleteEmbeddings(id, document.getTotalChunks());
                        repository.delete(document);
                        return ResponseEntity.noContent().<Void>build();
                    } catch (RuntimeException exception) {
                        return ResponseEntity.internalServerError().<Void>build();
                    }
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private boolean isPdf(MultipartFile file) {
        String name = file.getOriginalFilename();
        boolean extensionMatches = name != null && name.toLowerCase().endsWith(".pdf");
        try {
            byte[] header = file.getInputStream().readNBytes(5);
            return extensionMatches && new String(header, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        } catch (Exception exception) {
            return false;
        }
    }

    private Map<String, Object> toMetadata(UserDocument document) {
        String status = document.getEmbeddingStatus() == null
            ? (document.isEmbeddingReady() ? "READY" : "QUEUED")
            : document.getEmbeddingStatus();
        int progress = document.isEmbeddingReady() ? 100 : document.getEmbeddingProgress();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("id", document.getId());
        metadata.put("fileName", document.getFileName());
        metadata.put("size", document.getSize());
        metadata.put("embeddingReady", document.isEmbeddingReady());
        metadata.put("embeddingStatus", status);
        metadata.put("embeddingProgress", progress);
        metadata.put("processedChunks", document.getProcessedChunks());
        metadata.put("totalChunks", document.getTotalChunks());
        metadata.put("embeddingError", document.getEmbeddingError());
        metadata.put("uploadedAt", document.getUploadedAt().toString());
        return metadata;
    }
}
