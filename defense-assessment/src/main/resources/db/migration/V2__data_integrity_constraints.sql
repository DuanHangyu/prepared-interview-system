-- Fail before any ALTER TABLE if legacy data cannot satisfy the new business
-- keys. This deliberately preserves every row for an operator-led repair.
DROP PROCEDURE IF EXISTS flyway_precheck_v2_integrity;
DELIMITER //
CREATE PROCEDURE flyway_precheck_v2_integrity()
BEGIN
  IF EXISTS (
    SELECT 1 FROM `student_assessment_record`
    WHERE `assessment_id` IS NULL OR `student_id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: student_assessment_record contains null business keys';
  END IF;
  IF EXISTS (
    SELECT 1 FROM `student_assessment_record`
    GROUP BY `assessment_id`, `student_id` HAVING COUNT(*) > 1
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: duplicate student assessment records';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `student_assessment_appointment`
    WHERE `assessment_id` IS NULL OR `student_id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: student_assessment_appointment contains null business keys';
  END IF;
  IF EXISTS (
    SELECT 1 FROM `student_assessment_appointment`
    GROUP BY `assessment_id`, `student_id` HAVING COUNT(*) > 1
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: duplicate student assessment appointments';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `assessment_student_relation`
    WHERE `assessment_id` IS NULL OR `student_id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: assessment_student_relation contains null business keys';
  END IF;
  IF EXISTS (
    SELECT 1 FROM `assessment_student_relation`
    GROUP BY `assessment_id`, `student_id` HAVING COUNT(*) > 1
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: duplicate assessment student relations';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `assessment_invite`
    WHERE `assessment_id` IS NULL OR `invitee_teacher_id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: assessment_invite contains null business keys';
  END IF;
  IF EXISTS (
    SELECT 1 FROM `assessment_invite`
    GROUP BY `assessment_id`, `invitee_teacher_id` HAVING COUNT(*) > 1
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V2 blocked: duplicate assessment teacher invites';
  END IF;
END//
DELIMITER ;

CALL flyway_precheck_v2_integrity();
DROP PROCEDURE flyway_precheck_v2_integrity;

ALTER TABLE `student_assessment_record`
  MODIFY `assessment_id` INT NOT NULL,
  MODIFY `student_id` INT NOT NULL,
  ADD UNIQUE KEY `uk_record_assessment_student` (`assessment_id`, `student_id`);

ALTER TABLE `student_assessment_appointment`
  MODIFY `assessment_id` INT NOT NULL,
  MODIFY `student_id` INT NOT NULL,
  ADD UNIQUE KEY `uk_appointment_assessment_student` (`assessment_id`, `student_id`);

ALTER TABLE `assessment_student_relation`
  MODIFY `assessment_id` INT NOT NULL,
  MODIFY `student_id` INT NOT NULL,
  ADD UNIQUE KEY `uk_relation_assessment_student` (`assessment_id`, `student_id`);

ALTER TABLE `assessment_invite`
  MODIFY `assessment_id` INT NOT NULL,
  MODIFY `invitee_teacher_id` INT NOT NULL,
  ADD UNIQUE KEY `uk_invite_assessment_teacher` (`assessment_id`, `invitee_teacher_id`);
