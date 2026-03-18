package com.exam.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.exam.entity.OriginalProcessedPaper;
import com.exam.repository.OriginalProcessedPaperRepository;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Service
public class StorageMaintenanceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageMaintenanceService.class);

    private final OriginalProcessedPaperRepository originalProcessedPaperRepository;
    private final Gson gson = new Gson();

    @Value("${app.maintenance.enabled:true}")
    private boolean maintenanceEnabled;

    @Value("${app.maintenance.batch-size:200}")
    private int batchSize;

    public StorageMaintenanceService(OriginalProcessedPaperRepository originalProcessedPaperRepository) {
        this.originalProcessedPaperRepository = originalProcessedPaperRepository;
    }

    @Scheduled(fixedDelayString = "${app.maintenance.interval-ms:21600000}")
    public void runQuestionCountBackfill() {
        if (!maintenanceEnabled) {
            return;
        }

        int safeBatchSize = Math.max(20, Math.min(batchSize, 1000));
        Pageable pageable = PageRequest.of(0, safeBatchSize);
        Page<OriginalProcessedPaper> missingCountPage = originalProcessedPaperRepository.findByQuestionCountIsNull(pageable);
        if (missingCountPage.isEmpty()) {
            return;
        }

        List<OriginalProcessedPaper> rows = missingCountPage.getContent();
        for (OriginalProcessedPaper paper : rows) {
            if (paper == null) {
                continue;
            }
            paper.setQuestionCount(countQuestionsJson(paper.getOriginalQuestionsJson()));
        }

        originalProcessedPaperRepository.saveAll(rows);
        LOGGER.info("Storage maintenance backfilled question counts for {} processed papers.", rows.size());
    }

    private int countQuestionsJson(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        try {
            List<?> parsed = gson.fromJson(json, new TypeToken<List<?>>() { }.getType());
            return parsed == null ? 0 : parsed.size();
        } catch (Exception ignored) {
            return 0;
        }
    }
}
