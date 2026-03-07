package com.exam.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.exam.entity.QuestionBankItem;

@Repository
public interface QuestionBankItemRepository extends JpaRepository<QuestionBankItem, Long> {
    List<QuestionBankItem> findAllByOrderByCreatedAtDescIdDesc();
    List<QuestionBankItem> findBySubjectIgnoreCaseOrderByCreatedAtDescIdDesc(String subject);
    void deleteBySourceExamId(String sourceExamId);
}