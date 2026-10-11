-- Backfill nullable legacy timestamps before making retention cutoffs reliable.
UPDATE `token_consumer`
SET `create_time` = CURRENT_TIMESTAMP
WHERE `create_time` IS NULL;

UPDATE `student_assessment_record`
SET `create_time` = COALESCE(`create_time`, `start_defense_time`, `end_defense_time`, CURRENT_TIMESTAMP),
    `update_time` = COALESCE(`update_time`, `end_defense_time`, `start_defense_time`, `create_time`, CURRENT_TIMESTAMP)
WHERE `create_time` IS NULL OR `update_time` IS NULL;

UPDATE `student_assessment_question_answer`
SET `create_time` = CURRENT_TIMESTAMP
WHERE `create_time` IS NULL;

ALTER TABLE `token_consumer`
  MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD INDEX `idx_token_consumer_create_time` (`create_time`);

ALTER TABLE `student_assessment_record`
  MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD INDEX `idx_record_retention` (`state`, `end_defense_time`, `update_time`);

ALTER TABLE `student_assessment_question_answer`
  MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE `assessment`
  ADD COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  ADD COLUMN `deleted_at` DATETIME NULL,
  ADD INDEX `idx_assessment_retention` (`deleted`, `deleted_at`);
