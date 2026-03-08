package com.exam.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadStorageService {

    private final Path rootDirectory;

    public UploadStorageService(@Value("${app.storage.upload-root:uploads}") String uploadRoot) {
        this.rootDirectory = Paths.get(uploadRoot).toAbsolutePath().normalize();
    }

    public StoredFile store(String category, String ownerKey, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }

        String safeCategory = sanitizeSegment(category);
        String safeOwnerKey = sanitizeSegment(ownerKey);
        String originalFilename = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        String safeOriginalFilename = sanitizeFilename(originalFilename);

        Path targetDirectory = rootDirectory.resolve(safeCategory).resolve(safeOwnerKey);
        Files.createDirectories(targetDirectory);

        String storedFilename = UUID.randomUUID().toString().replace("-", "") + "_" + safeOriginalFilename;
        Path targetPath = targetDirectory.resolve(storedFilename).normalize();

        byte[] bytes = file.getBytes();
        Files.write(targetPath, bytes);

        String checksum = sha256(bytes);
        long size = bytes.length;

        String relativePath = rootDirectory.relativize(targetPath).toString().replace('\\', '/');
        return new StoredFile(originalFilename, relativePath, checksum, size);
    }

    private String sanitizeSegment(String value) {
        String input = value == null ? "default" : value.trim();
        if (input.isBlank()) {
            return "default";
        }
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String sanitizeFilename(String value) {
        String input = value == null ? "upload.bin" : value.trim();
        if (input.isBlank()) {
            return "upload.bin";
        }
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception exception) {
            return "";
        }
    }

    public record StoredFile(String originalFilename, String relativePath, String checksum, long size) {
    }
}
