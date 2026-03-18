package com.exam.service;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.exam.entity.ExamSubmission;
import com.exam.repository.ExamSubmissionRepository;
import com.google.gson.Gson;

@Service
public class SubmissionRetentionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionRetentionService.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ExamSubmissionRepository examSubmissionRepository;
    private final Gson gson = new Gson();
    private final Path archiveDirectory;

    @Value("${app.retention.enabled:true}")
    private boolean retentionEnabled;

    @Value("${app.retention.archive-after-days:365}")
    private int archiveAfterDays;

    @Value("${app.retention.batch-size:300}")
    private int batchSize;

    @Value("${app.retention.delete-after-archive:false}")
    private boolean deleteAfterArchive;

    public SubmissionRetentionService(ExamSubmissionRepository examSubmissionRepository,
                                      @Value("${app.storage.upload-root:uploads}") String uploadRoot) {
        this.examSubmissionRepository = examSubmissionRepository;
        this.archiveDirectory = Paths.get(uploadRoot)
            .toAbsolutePath()
            .normalize()
            .resolve("archive")
            .resolve("exam-submissions");
    }

    @Scheduled(fixedDelayString = "${app.retention.interval-ms:86400000}")
    public void archiveOldSubmissions() {
        if (!retentionEnabled) {
            return;
        }

        int safeDays = Math.max(30, archiveAfterDays);
        int safeBatchSize = Math.max(50, Math.min(batchSize, 2000));

        LocalDateTime cutoff = LocalDateTime.now().minusDays(safeDays);
        Pageable pageable = PageRequest.of(0, safeBatchSize);
        Page<ExamSubmission> oldSubmissionPage = examSubmissionRepository
            .findBySubmittedAtBeforeOrderBySubmittedAtAsc(cutoff, pageable);

        if (oldSubmissionPage.isEmpty()) {
            return;
        }

        List<ExamSubmission> rows = oldSubmissionPage.getContent();
        List<Long> archivedIds = new ArrayList<>();

        try {
            Files.createDirectories(archiveDirectory);
            String filename = "exam-submissions-"
                + LocalDate.now()
                + "-"
                + LocalDateTime.now().format(TS_FORMAT)
                + ".jsonl.gz";
            Path targetFile = archiveDirectory.resolve(filename);

            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(targetFile)),
                    StandardCharsets.UTF_8
                )
            )) {
                for (ExamSubmission row : rows) {
                    if (row == null || row.getId() == null) {
                        continue;
                    }
                    writer.write(gson.toJson(toArchiveRecord(row)));
                    writer.newLine();
                    archivedIds.add(row.getId());
                }
            }

            if (deleteAfterArchive && !archivedIds.isEmpty()) {
                examSubmissionRepository.deleteByIdIn(archivedIds);
            }

            LOGGER.info(
                "Submission retention archived {} row(s) older than {} days to {}. deleteAfterArchive={}",
                archivedIds.size(),
                safeDays,
                targetFile.toString(),
                deleteAfterArchive
            );
        } catch (Exception exception) {
            LOGGER.error("Submission retention failed.", exception);
        }
    }

    private Map<String, Object> toArchiveRecord(ExamSubmission row) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", row.getId());
        data.put("studentEmail", row.getStudentEmail());
        data.put("examName", row.getExamName());
        data.put("subject", row.getSubject());
        data.put("activityType", row.getActivityType());
        data.put("score", row.getScore());
        data.put("totalQuestions", row.getTotalQuestions());
        data.put("percentage", row.getPercentage());
        data.put("resultsReleased", row.isResultsReleased());
        data.put("submittedAt", row.getSubmittedAt());
        data.put("releasedAt", row.getReleasedAt());
        data.put("answerDetailsJson", row.getAnswerDetailsJson());
        data.put("topicMastery", row.getTopicMastery());
        data.put("difficultyResilience", row.getDifficultyResilience());
        data.put("accuracy", row.getAccuracy());
        data.put("timeEfficiency", row.getTimeEfficiency());
        data.put("confidence", row.getConfidence());
        data.put("performanceCategory", row.getPerformanceCategory());
        data.put("classification", row.getClassification());
        data.put("manualScore", row.getManualScore());
        data.put("graded", row.isGraded());
        data.put("gradedAt", row.getGradedAt());
        data.put("teacherComments", row.getTeacherComments());
        data.put("archivedAt", LocalDateTime.now());
        return data;
    }
}
