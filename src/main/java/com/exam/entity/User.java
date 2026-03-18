package com.exam.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

@Entity
@Table(
    name = "users",
    indexes = {
        @Index(name = "idx_users_role_department", columnList = "role, department_name"),
        @Index(name = "idx_users_department_program", columnList = "department_name, program_name")
    }
)
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

    @Column(length = 120)
    private String verificationCodeHash;

    private LocalDateTime verificationCodeExpiresAt;

    private LocalDateTime verificationCodeSentAt;
    
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

    public String getVerificationCodeHash() { return verificationCodeHash; }
    public void setVerificationCodeHash(String verificationCodeHash) { this.verificationCodeHash = verificationCodeHash; }

    public LocalDateTime getVerificationCodeExpiresAt() { return verificationCodeExpiresAt; }
    public void setVerificationCodeExpiresAt(LocalDateTime verificationCodeExpiresAt) { this.verificationCodeExpiresAt = verificationCodeExpiresAt; }

    public LocalDateTime getVerificationCodeSentAt() { return verificationCodeSentAt; }
    public void setVerificationCodeSentAt(LocalDateTime verificationCodeSentAt) { this.verificationCodeSentAt = verificationCodeSentAt; }
    
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    
    // Role Enum
    public enum Role {
        STUDENT, TEACHER, DEPARTMENT_ADMIN
    }
}
