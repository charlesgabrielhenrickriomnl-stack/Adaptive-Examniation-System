package com.exam.entity;

import java.time.LocalDateTime;

import com.exam.persistence.CompressedJsonConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * Distributed exam assignment metadata only.
 * Question content was intentionally removed from this entity and must be
 * loaded at runtime from OriginalProcessedPaper by exam ID.
 *
 * This entity stores assignment metadata such as student email, exam ID
 * reference, subject, exam name, activity type, time limit, deadline,
 * submission status, question indexes, and OTP fields.
 */
@Entity
@Table(
    name = "distributed_exams",
    indexes = {
        @Index(name = "idx_distributed_student_submitted", columnList = "student_email, submitted"),
        @Index(name = "idx_distributed_exam", columnList = "exam_id"),
        @Index(name = "idx_distributed_subject_submitted", columnList = "subject, submitted")
    }
)
public class DistributedExam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String studentEmail;

    @Column(nullable = false)
    private String examId;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String examName;

    @Column(nullable = false)
    private String activityType;

    @Column(nullable = false)
    private Integer timeLimit;

    @Column
    private String deadline;

    @Column(nullable = false)
    private LocalDateTime distributedAt;

    @Column(nullable = false)
    private boolean submitted = false;

    @Convert(converter = CompressedJsonConverter.class)
    @Column(name = "question_indexes_json", columnDefinition = "TEXT")
    private String questionIndexesJson;

    @Column(name = "access_otp_hash", length = 120)
    private String accessOtpHash;

    @Column(name = "access_otp_generated_at")
    private LocalDateTime accessOtpGeneratedAt;

    @Column(name = "access_otp_expires_at")
    private LocalDateTime accessOtpExpiresAt;

    @Column(name = "access_otp_verified_at")
    private LocalDateTime accessOtpVerifiedAt;

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStudentEmail() { return studentEmail; }
    public void setStudentEmail(String studentEmail) { this.studentEmail = studentEmail; }
    public String getExamId() { return examId; }
    public void setExamId(String examId) { this.examId = examId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getExamName() { return examName; }
    public void setExamName(String examName) { this.examName = examName; }
    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }
    public Integer getTimeLimit() { return timeLimit; }
    public void setTimeLimit(Integer timeLimit) { this.timeLimit = timeLimit; }
    /**
     * @deprecated Question content is no longer stored on DistributedExam.
     *             Load question rows from OriginalProcessedPaper via
     *             findByExamId(examId).
     */
    @Deprecated
    public String getQuestionsJson() { return null; }
    /**
     * @deprecated Question content is no longer stored on DistributedExam.
     *             Load question rows from OriginalProcessedPaper via
     *             findByExamId(examId).
     */
    @Deprecated
    public void setQuestionsJson(String questionsJson) { /* intentional no-op */ }
    /**
     * @deprecated Question content is no longer stored on DistributedExam.
     *             Load difficulty map from OriginalProcessedPaper via
     *             findByExamId(examId).
     */
    @Deprecated
    public String getDifficultiesJson() { return null; }
    /**
     * @deprecated Question content is no longer stored on DistributedExam.
     *             Load difficulty map from OriginalProcessedPaper via
     *             findByExamId(examId).
     */
    @Deprecated
    public void setDifficultiesJson(String difficultiesJson) { /* intentional no-op */ }
    /**
     * @deprecated Question content is no longer stored on DistributedExam.
     *             Load answer key from OriginalProcessedPaper via
     *             findByExamId(examId).
     */
    @Deprecated
    public String getAnswerKeyJson() { return null; }
    /**
     * @deprecated Question content is no longer stored on DistributedExam.
     *             Load answer key from OriginalProcessedPaper via
     *             findByExamId(examId).
     */
    @Deprecated
    public void setAnswerKeyJson(String answerKeyJson) { /* intentional no-op */ }
    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }
    public LocalDateTime getDistributedAt() { return distributedAt; }
    public void setDistributedAt(LocalDateTime distributedAt) { this.distributedAt = distributedAt; }
    public boolean isSubmitted() { return submitted; }
    public void setSubmitted(boolean submitted) { this.submitted = submitted; }
    public String getQuestionIndexesJson() { return questionIndexesJson; }
    public void setQuestionIndexesJson(String questionIndexesJson) { this.questionIndexesJson = questionIndexesJson; }
    public String getAccessOtpHash() { return accessOtpHash; }
    public void setAccessOtpHash(String accessOtpHash) { this.accessOtpHash = accessOtpHash; }
    public LocalDateTime getAccessOtpGeneratedAt() { return accessOtpGeneratedAt; }
    public void setAccessOtpGeneratedAt(LocalDateTime accessOtpGeneratedAt) { this.accessOtpGeneratedAt = accessOtpGeneratedAt; }
    public LocalDateTime getAccessOtpExpiresAt() { return accessOtpExpiresAt; }
    public void setAccessOtpExpiresAt(LocalDateTime accessOtpExpiresAt) { this.accessOtpExpiresAt = accessOtpExpiresAt; }
    public LocalDateTime getAccessOtpVerifiedAt() { return accessOtpVerifiedAt; }
    public void setAccessOtpVerifiedAt(LocalDateTime accessOtpVerifiedAt) { this.accessOtpVerifiedAt = accessOtpVerifiedAt; }
}
