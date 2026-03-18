package com.exam.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "department_sharing_settings",
    uniqueConstraints = @UniqueConstraint(columnNames = {"department_name"})
)
public class DepartmentSharingSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "department_name", nullable = false, length = 180)
    private String departmentName;

    @Column(name = "teacher_pull_enabled", nullable = false)
    private boolean teacherPullEnabled;

    @Column(name = "updated_by_email", length = 255)
    private String updatedByEmail;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    @SuppressWarnings("unused")
    void touchUpdatedAt() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDepartmentName() {
        return departmentName;
    }

    public void setDepartmentName(String departmentName) {
        this.departmentName = departmentName;
    }

    public boolean isTeacherPullEnabled() {
        return teacherPullEnabled;
    }

    public void setTeacherPullEnabled(boolean teacherPullEnabled) {
        this.teacherPullEnabled = teacherPullEnabled;
    }

    public String getUpdatedByEmail() {
        return updatedByEmail;
    }

    public void setUpdatedByEmail(String updatedByEmail) {
        this.updatedByEmail = updatedByEmail;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
