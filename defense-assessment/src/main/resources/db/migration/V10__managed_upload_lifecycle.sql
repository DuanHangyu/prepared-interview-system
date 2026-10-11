CREATE TABLE `managed_upload` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `object_reference` VARCHAR(512) NOT NULL,
  `owner_id` INT NOT NULL,
  `object_size` BIGINT NOT NULL,
  `state` TINYINT NOT NULL DEFAULT 0,
  `attempts` INT NOT NULL DEFAULT 0,
  `expires_at` DATETIME NOT NULL,
  `last_error` VARCHAR(255) DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_managed_upload_reference` (`object_reference`),
  KEY `idx_managed_upload_due` (`state`, `expires_at`, `id`),
  CONSTRAINT `chk_managed_upload_state` CHECK (`state` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
