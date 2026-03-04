package com.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.exam.entity.DistributedExam;

public interface DistributedExamRepository extends JpaRepository<DistributedExam, Long> {
    List<DistributedExam> findByStudentEmail(String studentEmail);
    List<DistributedExam> findBySubject(String subject);
    List<DistributedExam> findByExamId(String examId);
    List<DistributedExam> findBySubjectAndSubmittedFalse(String subject);
    List<DistributedExam> findByStudentEmailAndSubmittedFalse(String studentEmail);
    void deleteByExamIdAndStudentEmail(String examId, String studentEmail);
}
