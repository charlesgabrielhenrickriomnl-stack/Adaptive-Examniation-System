package com.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.exam.entity.ExamSubmission;

@Repository
public interface ExamSubmissionRepository extends JpaRepository<ExamSubmission, Long> {
    List<ExamSubmission> findByStudentEmail(String studentEmail);
    List<ExamSubmission> findByStudentEmailAndExamName(String studentEmail, String examName);
    List<ExamSubmission> findByStudentEmailAndExamNameAndSubject(String studentEmail, String examName, String subject);
    List<ExamSubmission> findByExamNameAndSubject(String examName, String subject);
    List<ExamSubmission> findByResultsReleasedFalse(); // Pending release
    List<ExamSubmission> findByIsGradedFalse(); // Pending teacher grading
    List<ExamSubmission> findByStudentEmailIn(List<String> studentEmails);
}
