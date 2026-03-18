package com.exam.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "subjects",
    indexes = {
        @Index(name = "idx_subject_teacher", columnList = "teacher_email")
    }
)
public class Subject {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String subjectName;
    
    @Column
    private String description;
    
    @Column(name = "teacher_email", nullable = false)
    private String teacherEmail;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    // Constructors
    public Subject() {
        this.createdAt = LocalDateTime.now();
    }
    
    public Subject(String subjectName, String description, String teacherEmail) {
        this.subjectName = subjectName;
        this.description = description;
        this.teacherEmail = teacherEmail;
        this.createdAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getTeacherEmail() { return teacherEmail; }
    public void setTeacherEmail(String teacherEmail) { this.teacherEmail = teacherEmail; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
