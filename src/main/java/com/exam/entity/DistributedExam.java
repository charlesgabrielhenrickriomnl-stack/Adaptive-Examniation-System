package com.exam.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "distributed_exams")
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

    @Column(name = "questions_json", columnDefinition = "LONGTEXT")
    private String questionsJson;

    @Column(name = "difficulties_json", columnDefinition = "LONGTEXT")
    private String difficultiesJson;

    @Column(name = "answer_key_json", columnDefinition = "LONGTEXT")
    private String answerKeyJson;

    @Column
    private String deadline;

    @Column(nullable = false)
    private LocalDateTime distributedAt;

    @Column(nullable = false)
    private boolean submitted = false;

    @Column(name = "question_indexes_json", columnDefinition = "TEXT")
    private String questionIndexesJson;

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
    public String getQuestionsJson() { return questionsJson; }
    public void setQuestionsJson(String questionsJson) { this.questionsJson = questionsJson; }
    public String getDifficultiesJson() { return difficultiesJson; }
    public void setDifficultiesJson(String difficultiesJson) { this.difficultiesJson = difficultiesJson; }
    public String getAnswerKeyJson() { return answerKeyJson; }
    public void setAnswerKeyJson(String answerKeyJson) { this.answerKeyJson = answerKeyJson; }
    public String getDeadline() { return deadline; }
    public void setDeadline(String deadline) { this.deadline = deadline; }
    public LocalDateTime getDistributedAt() { return distributedAt; }
    public void setDistributedAt(LocalDateTime distributedAt) { this.distributedAt = distributedAt; }
    public boolean isSubmitted() { return submitted; }
    public void setSubmitted(boolean submitted) { this.submitted = submitted; }
    public String getQuestionIndexesJson() { return questionIndexesJson; }
    public void setQuestionIndexesJson(String questionIndexesJson) { this.questionIndexesJson = questionIndexesJson; }
}
