-- Baseline for fresh installations. Existing non-empty databases are baselined
-- at version 1 and receive the integrity upgrade in V2.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `user` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) DEFAULT NULL,
  `account` VARCHAR(255) NOT NULL,
  `password` VARCHAR(255) NOT NULL,
  `phone` VARCHAR(32) DEFAULT NULL,
  `type` INT DEFAULT NULL COMMENT '0=student,1=teacher,2=admin',
  `school_class` VARCHAR(255) DEFAULT NULL,
  `ability_advantage_analysis` TEXT,
  `development_potential_assessment` TEXT,
  `create_time` DATETIME DEFAULT NULL,
  `update_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_account` (`account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `assessment` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `theme` VARCHAR(500) DEFAULT NULL,
  `assessment_requirements` TEXT,
  `assessment_criteria` TEXT,
  `total_score` INT DEFAULT NULL,
  `pass_score` INT DEFAULT NULL,
  `duration` INT DEFAULT NULL,
  `question_count` INT DEFAULT NULL,
  `answer_time` INT DEFAULT NULL,
  `follow_up` TINYINT(1) DEFAULT 0,
  `follow_up_prompt` TEXT,
  `follow_up_standards` TEXT,
  `assessment_files` TEXT,
  `defense` TINYINT(1) DEFAULT 0,
  `question` TINYINT(1) DEFAULT 0,
  `show_result` TINYINT(1) DEFAULT 0,
  `create_id` INT DEFAULT NULL,
  `need_appoint` TINYINT(1) DEFAULT 0,
  `assessment_fail_punish` TINYINT(1) DEFAULT 0,
  `reschedule_appoint_time` DATETIME DEFAULT NULL,
  `last_appoint_time` DATETIME DEFAULT NULL,
  `digital_human_id` INT DEFAULT NULL,
  `deleted` TINYINT(1) DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_assessment_create_id` (`create_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `assessment_appointment_setting` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `assessment_id` INT DEFAULT NULL,
  `time_period` DATETIME DEFAULT NULL,
  `participant_limit` INT DEFAULT NULL,
  `location` VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_aas_assessment_id` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `assessment_invite` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `assessment_id` INT DEFAULT NULL,
  `invitee_teacher_id` INT DEFAULT NULL,
  `inviter_teacher_id` INT DEFAULT NULL,
  `create_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ai_assessment_id` (`assessment_id`),
  KEY `idx_ai_invitee_teacher_id` (`invitee_teacher_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `assessment_student_relation` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `assessment_id` INT DEFAULT NULL,
  `student_id` INT DEFAULT NULL,
  `relation_type` INT DEFAULT NULL,
  `create_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_asr_assessment_id` (`assessment_id`),
  KEY `idx_asr_student_id` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `digital_human` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) DEFAULT NULL,
  `video` VARCHAR(1000) DEFAULT NULL,
  `lip_shape` VARCHAR(1000) DEFAULT NULL,
  `default_flag` TINYINT(1) DEFAULT 0,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `lock_key` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `lock_key_name` VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lock_key_name` (`lock_key_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `project_type` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `project_type_key` VARCHAR(255) DEFAULT NULL,
  `project_type` VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `student_assessment_appointment` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `student_id` INT DEFAULT NULL,
  `assessment_id` INT DEFAULT NULL,
  `appointment_time` DATETIME DEFAULT NULL,
  `time_period` DATETIME DEFAULT NULL,
  `state` INT DEFAULT NULL,
  `punish_state` INT DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saa_student_id` (`student_id`),
  KEY `idx_saa_assessment_id` (`assessment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `student_assessment_question_answer` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `assessment_id` INT DEFAULT NULL,
  `student_id` INT DEFAULT NULL,
  `question` TEXT,
  `answer` TEXT,
  `answer_voice` VARCHAR(1000) DEFAULT NULL,
  `follow_question` TEXT,
  `follow_answer` TEXT,
  `follow_answer_voice` VARCHAR(1000) DEFAULT NULL,
  `create_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_saqa_assessment_id` (`assessment_id`),
  KEY `idx_saqa_student_id` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `student_assessment_record` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `assessment_id` INT DEFAULT NULL,
  `student_id` INT DEFAULT NULL,
  `defense_file` VARCHAR(1000) DEFAULT NULL,
  `defense_content` TEXT,
  `defense_voice` VARCHAR(1000) DEFAULT NULL,
  `score` INT DEFAULT NULL,
  `check_score` INT DEFAULT NULL,
  `defense_time` INT DEFAULT NULL,
  `start_defense_time` DATETIME DEFAULT NULL,
  `end_defense_time` DATETIME DEFAULT NULL,
  `defense_result` TEXT,
  `state` INT DEFAULT NULL,
  `re_analysis` TINYINT(1) DEFAULT 0,
  `create_time` DATETIME DEFAULT NULL,
  `update_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_sar_assessment_id` (`assessment_id`),
  KEY `idx_sar_student_id` (`student_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `token_consumer` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `method` VARCHAR(255) DEFAULT NULL,
  `param` TEXT,
  `result` TEXT,
  `prompt_token_count` INT DEFAULT NULL,
  `candidates_token_count` INT DEFAULT NULL,
  `total_token_count` INT DEFAULT NULL,
  `cached_content_token_count` INT DEFAULT NULL,
  `cost_time` BIGINT DEFAULT NULL,
  `create_time` DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
