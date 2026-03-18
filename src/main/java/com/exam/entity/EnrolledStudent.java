package com.exam.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "enrolled_students",
    indexes = {
        @Index(name = "idx_enrollment_teacher_student", columnList = "teacher_email, student_email"),
        @Index(name = "idx_enrollment_subject", columnList = "subject_id"),
        @Index(name = "idx_enrollment_student", columnList = "student_email")
    }
)
public class EnrolledStudent {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "teacher_email", nullable = false)
    private String teacherEmail;
    
    @Column(name = "student_email", nullable = false)
    private String studentEmail;
    
    @Column(nullable = false)
    private String studentName;
    
    @Column(name = "subject_id", nullable = false)
    private Long subjectId;
    
    @Column
    private String subjectName;
    
    @Column(nullable = false)
    private LocalDateTime enrolledAt;
    
    // Constructors
    public EnrolledStudent() {
        this.enrolledAt = LocalDateTime.now();
    }
    
    public EnrolledStudent(String teacherEmail, String studentEmail, String studentName) {
        this.teacherEmail = teacherEmail;
        this.studentEmail = studentEmail;
        this.studentName = studentName;
        this.enrolledAt = LocalDateTime.now();
    }
    
    public EnrolledStudent(String teacherEmail, String studentEmail, String studentName, Long subjectId, String subjectName) {
        this.teacherEmail = teacherEmail;
        this.studentEmail = studentEmail;
        this.studentName = studentName;
        this.subjectId = subjectId;
        this.subjectName = subjectName;
        this.enrolledAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getTeacherEmail() { return teacherEmail; }
    public void setTeacherEmail(String teacherEmail) { this.teacherEmail = teacherEmail; }
    
    public String getStudentEmail() { return studentEmail; }
    public void setStudentEmail(String studentEmail) { this.studentEmail = studentEmail; }
    
    public String getStudentName() { return studentName; }
    public void setStudentName(String studentName) { this.studentName = studentName; }
    
    public Long getSubjectId() { return subjectId; }
    public void setSubjectId(Long subjectId) { this.subjectId = subjectId; }
    
    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }
    
    public LocalDateTime getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(LocalDateTime enrolledAt) { this.enrolledAt = enrolledAt; }
}
