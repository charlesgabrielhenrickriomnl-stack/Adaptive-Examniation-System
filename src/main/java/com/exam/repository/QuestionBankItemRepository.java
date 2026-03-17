package com.exam.repository;

/**
 * Intentionally not a Spring Data repository.
 * QuestionBankItem is no longer a JPA entity; items are built in-memory via
 * buildTemporaryQuestionBankItems() and discarded after use.
 * Do not re-add @Repository or JpaRepository here.
 */
public final class QuestionBankItemRepository {

    private QuestionBankItemRepository() {
    }
}
