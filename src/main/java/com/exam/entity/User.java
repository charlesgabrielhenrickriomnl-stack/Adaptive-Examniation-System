package com.exam.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private String email;
    
    @Column(nullable = false)
    private String password;
    
    @Column(nullable = false)
    private String fullName;

    @Column(length = 255)
    private String schoolName;

    @Column(length = 255)
    private String campusName;

    @Column(length = 255)
    private String departmentName;

    @Column(length = 255)
    private String programName;
    
    @Enumerated(EnumType.STRING)
    private Role role; // STUDENT or TEACHER
    
    @Column(nullable = false)
    private boolean enabled = false;
    
    private String verificationToken;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String faceReferenceImageBase64;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String faceReferenceDescriptorJson;

    private LocalDateTime faceReferenceCreatedAt;
    
    private LocalDateTime createdAt;
    
    // Constructors
    public User() {
        this.createdAt = LocalDateTime.now();
    }
    
    public User(String email, String password, String fullName, Role role) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.role = role;
        this.createdAt = LocalDateTime.now();
    }
    
    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getSchoolName() { return schoolName; }
    public void setSchoolName(String schoolName) { this.schoolName = schoolName; }

    public String getCampusName() { return campusName; }
    public void setCampusName(String campusName) { this.campusName = campusName; }

    public String getDepartmentName() { return departmentName; }
    public void setDepartmentName(String departmentName) { this.departmentName = departmentName; }

    public String getProgramName() { return programName; }
    public void setProgramName(String programName) { this.programName = programName; }
    
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    
    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String token) { this.verificationToken = token; }

    public String getFaceReferenceImageBase64() { return faceReferenceImageBase64; }
    public void setFaceReferenceImageBase64(String faceReferenceImageBase64) { this.faceReferenceImageBase64 = faceReferenceImageBase64; }

    public String getFaceReferenceDescriptorJson() { return faceReferenceDescriptorJson; }
    public void setFaceReferenceDescriptorJson(String faceReferenceDescriptorJson) { this.faceReferenceDescriptorJson = faceReferenceDescriptorJson; }

    public LocalDateTime getFaceReferenceCreatedAt() { return faceReferenceCreatedAt; }
    public void setFaceReferenceCreatedAt(LocalDateTime faceReferenceCreatedAt) { this.faceReferenceCreatedAt = faceReferenceCreatedAt; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    // Role Enum
    public enum Role {
        STUDENT, TEACHER, DEPARTMENT_ADMIN
    }
}
