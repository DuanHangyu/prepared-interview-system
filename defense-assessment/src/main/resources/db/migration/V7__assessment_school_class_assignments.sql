-- Preserve class-level assessment assignments independently from the current
-- students in that class. Existing type-1 relations are materialized student
-- memberships; this table records the source relationship that created them.
CREATE TABLE `assessment_school_class` (
  `id` INT NOT NULL AUTO_INCREMENT,
  `assessment_id` INT NOT NULL,
  `school_class` VARCHAR(255) NOT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_assessment_school_class` (`assessment_id`, `school_class`),
  KEY `idx_assessment_school_class_class` (`school_class`),
  CONSTRAINT `fk_assessment_school_class_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Recover every class assignment that can be derived from a current type-1
-- student relation. Blank historical class values cannot identify a class and
-- are deliberately left as their existing per-student relation.
INSERT INTO `assessment_school_class` (`assessment_id`, `school_class`)
SELECT DISTINCT relation_row.`assessment_id`, student.`school_class`
FROM `assessment_student_relation` relation_row
JOIN `user` student ON student.`id` = relation_row.`student_id`
WHERE relation_row.`relation_type` = 1
  AND student.`school_class` IS NOT NULL
  AND TRIM(student.`school_class`) <> '';
