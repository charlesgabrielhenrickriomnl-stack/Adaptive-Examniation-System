/*
  Purpose:
  Remove legacy per-question persistence artifacts so question content exists only
  in processed_papers_original JSON fields.

  Notes:
  - Hibernate ddl-auto=update will not reliably drop removed tables/columns.
  - Run this migration once, manually, against the adaptiveexam MySQL database
    after deploying the code changes.
*/

DROP TABLE IF EXISTS question_bank_items;

ALTER TABLE distributed_exams
  DROP COLUMN IF EXISTS questions_json,
  DROP COLUMN IF EXISTS difficulties_json,
  DROP COLUMN IF EXISTS answer_key_json;

SELECT COLUMN_NAME
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'distributed_exams'
ORDER BY ORDINAL_POSITION;
