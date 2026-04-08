-- Adds explicit subject linkage for processed papers and backfills from subject name + owner.

ALTER TABLE processed_papers_original
    ADD COLUMN IF NOT EXISTS subject_id BIGINT NULL;

ALTER TABLE processed_papers_original
    ADD INDEX IF NOT EXISTS idx_processed_papers_subject_processed (subject_id, processed_at);

-- Backfill using owner + subject name match where possible.
UPDATE processed_papers_original p
JOIN subjects s
  ON LOWER(TRIM(p.teacher_email)) = LOWER(TRIM(s.teacher_email))
 AND LOWER(TRIM(p.subject)) = LOWER(TRIM(s.subject_name))
SET p.subject_id = s.id
WHERE p.subject_id IS NULL;

-- Add FK after backfill verification if desired.
-- ALTER TABLE processed_papers_original
--   ADD CONSTRAINT fk_processed_papers_subject
--   FOREIGN KEY (subject_id) REFERENCES subjects(id)
--   ON DELETE SET NULL;
