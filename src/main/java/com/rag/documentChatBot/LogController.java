package com.rag.documentChatBot;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/logs")
public class LogController {
    private final Path logFile;

    public LogController(@Value("${logging.file.name:logs/document-chatbot.log}") String logFile) {
        this.logFile = Path.of(logFile).toAbsolutePath().normalize();
    }

    @GetMapping(value = "/download", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<Resource> download() {
        if (!Files.isRegularFile(logFile)) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(logFile);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(logFile.getFileName().toString()).build().toString())
                .body(resource);
    }
}