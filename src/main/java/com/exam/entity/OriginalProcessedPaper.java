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

@Entity
@Table(
    name = "processed_papers_original",
    indexes = {
        @Index(name = "idx_processed_papers_teacher_processed", columnList = "teacher_email, processed_at"),
        @Index(name = "idx_processed_papers_subject_processed", columnList = "subject_id, processed_at"),
        @Index(name = "idx_processed_papers_department_processed", columnList = "department_name, processed_at"),
        @Index(name = "idx_processed_papers_dept_shared_processed", columnList = "department_name, teacher_pull_shared, processed_at")
    }
)
public class OriginalProcessedPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exam_id", nullable = false, unique = true, length = 64)
    private String examId;

    @Column(name = "teacher_email", nullable = false)
    private String teacherEmail;

    @Column(name = "original_teacher_email")
    private String originalTeacherEmail;

    @Column(name = "department_name")
    private String departmentName;

    @Column(name = "exam_name", nullable = false)
    private String examName;

    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "activity_type", nullable = false)
    private String activityType;

    @Column(name = "source_filename")
    private String sourceFilename;

    @Column(name = "source_file_path")
    private String sourceFilePath;

    @Column(name = "source_file_checksum", length = 64)
    private String sourceFileChecksum;

    @Column(name = "source_file_size")
    private Long sourceFileSize;

    @Column(name = "answer_key_filename")
    private String answerKeyFilename;

    @Column(name = "answer_key_file_path")
    private String answerKeyFilePath;

    @Column(name = "answer_key_file_checksum", length = 64)
    private String answerKeyFileChecksum;

    @Column(name = "answer_key_file_size")
    private Long answerKeyFileSize;

    @Column(name = "question_count")
    private Integer questionCount;

    @Column(name = "teacher_pull_shared", nullable = false)
    private boolean teacherPullShared;

    @Column(name = "sharing_scope", nullable = false, length = 20)
    private String sharingScope;

    @Column(name = "shared_program_name", length = 255)
    private String sharedProgramName;

    @Column(name = "shared_teacher_email", length = 255)
    private String sharedTeacherEmail;

    @Convert(converter = CompressedJsonConverter.class)
    @Column(name = "original_questions_json", columnDefinition = "LONGTEXT", nullable = false)
    private String originalQuestionsJson;

    @Convert(converter = CompressedJsonConverter.class)
    @Column(name = "difficulties_json", columnDefinition = "LONGTEXT")
    private String difficultiesJson;

    @Convert(converter = CompressedJsonConverter.class)
    @Column(name = "answer_key_json", columnDefinition = "LONGTEXT")
    private String answerKeyJson;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public OriginalProcessedPaper() {
        this.processedAt = LocalDateTime.now();
        this.questionCount = 0;
        this.teacherPullShared = false;
        this.sharingScope = "DEPARTMENT";
        this.sharedProgramName = null;
        this.sharedTeacherEmail = null;
    }

    public OriginalProcessedPaper(String examId,
                                  String teacherEmail,
                                  String examName,
                                  String subject,
                                  String activityType,
                                  String sourceFilename,
                                  String originalQuestionsJson,
                                  String difficultiesJson,
                                  String answerKeyJson) {
        this.examId = examId;
        this.teacherEmail = teacherEmail;
        this.examName = examName;
        this.subject = subject;
        this.activityType = activityType;
        this.sourceFilename = sourceFilename;
        this.originalQuestionsJson = originalQuestionsJson;
        this.difficultiesJson = difficultiesJson;
        this.answerKeyJson = answerKeyJson;
        this.questionCount = 0;
        this.teacherPullShared = false;
        this.sharingScope = "DEPARTMENT";
        this.sharedProgramName = null;
        this.sharedTeacherEmail = null;
        this.processedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExamId() { return examId; }
    public void setExamId(String examId) { this.examId = examId; }

    public String getTeacherEmail() { return teacherEmail; }
    public void setTeacherEmail(String teacherEmail) { this.teacherEmail = teacherEmail; }

    public String getOriginalTeacherEmail() { return originalTeacherEmail; }
    public void setOriginalTeacherEmail(String originalTeacherEmail) { this.originalTeacherEmail = originalTeacherEmail; }

    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }

    public String getExamName() { return examName; }
    public void setExamName(String examName) { this.examName = examName; }

    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }

    public String getSourceFilename() { return sourceFilename; }
    public void setSourceFilename(String sourceFilename) { this.sourceFilename = sourceFilename; }

    public String getSourceFilePath() { return sourceFilePath; }
    public void setSourceFilePath(String sourceFilePath) { this.sourceFilePath = sourceFilePath; }

    public String getSourceFileChecksum() { return sourceFileChecksum; }
    public void setSourceFileChecksum(String sourceFileChecksum) { this.sourceFileChecksum = sourceFileChecksum; }

    public Long getSourceFileSize() { return sourceFileSize; }
    public void setSourceFileSize(Long sourceFileSize) { this.sourceFileSize = sourceFileSize; }

    public String getAnswerKeyFilename() { return answerKeyFilename; }
    public void setAnswerKeyFilename(String answerKeyFilename) { this.answerKeyFilename = answerKeyFilename; }

    public String getAnswerKeyFilePath() { return answerKeyFilePath; }
    public void setAnswerKeyFilePath(String answerKeyFilePath) { this.answerKeyFilePath = answerKeyFilePath; }

    public String getAnswerKeyFileChecksum() { return answerKeyFileChecksum; }
    public void setAnswerKeyFileChecksum(String answerKeyFileChecksum) { this.answerKeyFileChecksum = answerKeyFileChecksum; }

    public Long getAnswerKeyFileSize() { return answerKeyFileSize; }
    public void setAnswerKeyFileSize(Long answerKeyFileSize) { this.answerKeyFileSize = answerKeyFileSize; }

    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }

    public boolean isTeacherPullShared() { return teacherPullShared; }
    public void setTeacherPullShared(boolean teacherPullShared) { this.teacherPullShared = teacherPullShared; }

    public String getSharingScope() { return sharingScope; }
    public void setSharingScope(String sharingScope) { this.sharingScope = sharingScope; }

    public String getSharedProgramName() { return sharedProgramName; }
    public void setSharedProgramName(String sharedProgramName) { this.sharedProgramName = sharedProgramName; }

    public String getSharedTeacherEmail() { return sharedTeacherEmail; }
    public void setSharedTeacherEmail(String sharedTeacherEmail) { this.sharedTeacherEmail = sharedTeacherEmail; }

    public String getOriginalQuestionsJson() { return originalQuestionsJson; }
    public void setOriginalQuestionsJson(String originalQuestionsJson) { this.originalQuestionsJson = originalQuestionsJson; }

    public String getDifficultiesJson() { return difficultiesJson; }
    public void setDifficultiesJson(String difficultiesJson) { this.difficultiesJson = difficultiesJson; }

    public String getAnswerKeyJson() { return answerKeyJson; }
    public void setAnswerKeyJson(String answerKeyJson) { this.answerKeyJson = answerKeyJson; }

    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }
}
