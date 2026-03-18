package com.exam.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Transparently compresses large JSON payloads before storing them in text columns.
 * Existing plain-text rows remain readable because only prefixed values are decompressed.
 */
@Converter
public class CompressedJsonConverter implements AttributeConverter<String, String> {

    private static final String PREFIX = "__GZ__";
    private static final int MIN_COMPRESSION_LENGTH = 512;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        if (attribute.startsWith(PREFIX) || attribute.length() < MIN_COMPRESSION_LENGTH) {
            return attribute;
        }

        try {
            byte[] compressed = compress(attribute);
            String encoded = Base64.getEncoder().encodeToString(compressed);
            String candidate = PREFIX + encoded;
            return candidate.length() < attribute.length() ? candidate : attribute;
        } catch (Exception ignored) {
            return attribute;
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank() || !dbData.startsWith(PREFIX)) {
            return dbData;
        }

        try {
            String payload = dbData.substring(PREFIX.length());
            byte[] decoded = Base64.getDecoder().decode(payload);
            return decompress(decoded);
        } catch (Exception ignored) {
            return dbData;
        }
    }

    private byte[] compress(String value) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private String decompress(byte[] value) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(value))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
