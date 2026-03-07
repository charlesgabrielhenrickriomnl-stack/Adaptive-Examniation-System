package com.exam.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "question_bank_items")
public class QuestionBankItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_exam_id", nullable = false, length = 64)
    private String sourceExamId;

    @Column(name = "source_exam_name", nullable = false)
    private String sourceExamName;

    @Column(name = "source_teacher_email")
    private String sourceTeacherEmail;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "activity_type", nullable = false)
    private String activityType;

    @Column(name = "question_order", nullable = false)
    private Integer questionOrder;

    @Column(name = "question_text", columnDefinition = "LONGTEXT", nullable = false)
    private String questionText;

    @Column(name = "choices_json", columnDefinition = "LONGTEXT")
    private String choicesJson;

    @Column(name = "answer_text", columnDefinition = "LONGTEXT")
    private String answerText;

    @Column(name = "difficulty", length = 32)
    private String difficulty;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public QuestionBankItem() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceExamId() { return sourceExamId; }
    public void setSourceExamId(String sourceExamId) { this.sourceExamId = sourceExamId; }

    public String getSourceExamName() { return sourceExamName; }
    public void setSourceExamName(String sourceExamName) { this.sourceExamName = sourceExamName; }

    public String getSourceTeacherEmail() { return sourceTeacherEmail; }
    public void setSourceTeacherEmail(String sourceTeacherEmail) { this.sourceTeacherEmail = sourceTeacherEmail; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getActivityType() { return activityType; }
    public void setActivityType(String activityType) { this.activityType = activityType; }

    public Integer getQuestionOrder() { return questionOrder; }
    public void setQuestionOrder(Integer questionOrder) { this.questionOrder = questionOrder; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public String getChoicesJson() { return choicesJson; }
    public void setChoicesJson(String choicesJson) { this.choicesJson = choicesJson; }

    public String getAnswerText() { return answerText; }
    public void setAnswerText(String answerText) { this.answerText = answerText; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}