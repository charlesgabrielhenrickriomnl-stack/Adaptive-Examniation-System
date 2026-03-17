package com.exam.entity;

import java.time.LocalDateTime;

/**
 * Transient in-memory projection derived from OriginalProcessedPaper.
 * This class is never persisted to the database; question items are built at
 * runtime via buildTemporaryQuestionBankItems() and discarded after use.
 */
public class QuestionBankItem {

    private Long id;

    private String sourceExamId;

    private String sourceExamName;

    private String sourceTeacherEmail;

    private String sourceTeacherDepartment;

    private String subject;

    private String activityType;

    private Integer questionOrder;

    private String questionText;

    private String choicesJson;

    private String answerText;

    private String difficulty;

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

    public String getSourceTeacherDepartment() { return sourceTeacherDepartment; }
    public void setSourceTeacherDepartment(String sourceTeacherDepartment) { this.sourceTeacherDepartment = sourceTeacherDepartment; }

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