package com.exam.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.exam.entity.OriginalProcessedPaper;

@Repository
public interface OriginalProcessedPaperRepository extends JpaRepository<OriginalProcessedPaper, Long> {
    Optional<OriginalProcessedPaper> findByExamId(String examId);
    List<OriginalProcessedPaper> findByTeacherEmailOrderByProcessedAtDesc(String teacherEmail);
    List<OriginalProcessedPaper> findByTeacherEmailIgnoreCaseOrderByProcessedAtDesc(String teacherEmail);
    Page<OriginalProcessedPaper> findByTeacherEmailIgnoreCaseOrderByProcessedAtDesc(String teacherEmail, Pageable pageable);
    Page<OriginalProcessedPaper> findByTeacherEmailIgnoreCaseAndExamNameContainingIgnoreCaseOrderByProcessedAtDesc(
        String teacherEmail,
        String examName,
        Pageable pageable
    );
    List<OriginalProcessedPaper> findByDepartmentNameIgnoreCaseOrderByProcessedAtDesc(String departmentName);
    Page<OriginalProcessedPaper> findByDepartmentNameIgnoreCaseAndTeacherEmailNotIgnoreCaseOrderByProcessedAtDesc(
        String departmentName,
        String teacherEmail,
        Pageable pageable
    );
    Page<OriginalProcessedPaper> findByDepartmentNameIgnoreCaseAndTeacherEmailNotIgnoreCaseAndExamNameContainingIgnoreCaseOrderByProcessedAtDesc(
        String departmentName,
        String teacherEmail,
        String examName,
        Pageable pageable
    );
    Page<OriginalProcessedPaper> findByQuestionCountIsNull(Pageable pageable);
}
