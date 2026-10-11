-- Enforce the ownership and parent/child references used by the application.
-- Existing rows are never repaired or deleted automatically: operators receive
-- a precise blocking error and can correct legacy data before retrying Flyway.
DROP PROCEDURE IF EXISTS flyway_precheck_v5_references;
DELIMITER //
CREATE PROCEDURE flyway_precheck_v5_references()
BEGIN
  IF EXISTS (
    SELECT 1 FROM `assessment` child
    LEFT JOIN `user` parent ON parent.`id` = child.`create_id`
    WHERE child.`create_id` IS NULL OR parent.`id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: assessment has a missing creator';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `assessment_invite` child
    LEFT JOIN `assessment` parent ON parent.`id` = child.`assessment_id`
    LEFT JOIN `user` invitee ON invitee.`id` = child.`invitee_teacher_id`
    LEFT JOIN `user` inviter ON inviter.`id` = child.`inviter_teacher_id`
    WHERE parent.`id` IS NULL OR invitee.`id` IS NULL OR inviter.`id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: assessment_invite has a missing reference';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `assessment_student_relation` child
    LEFT JOIN `assessment` assessment_parent ON assessment_parent.`id` = child.`assessment_id`
    LEFT JOIN `user` student_parent ON student_parent.`id` = child.`student_id`
    WHERE assessment_parent.`id` IS NULL
       OR student_parent.`id` IS NULL
       OR child.`relation_type` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: assessment_student_relation has a missing reference';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `assessment_appointment_setting` child
    LEFT JOIN `assessment` parent ON parent.`id` = child.`assessment_id`
    WHERE child.`assessment_id` IS NULL OR parent.`id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: assessment_appointment_setting has a missing assessment';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `student_assessment_appointment` child
    LEFT JOIN `assessment` assessment_parent ON assessment_parent.`id` = child.`assessment_id`
    LEFT JOIN `user` student_parent ON student_parent.`id` = child.`student_id`
    WHERE assessment_parent.`id` IS NULL OR student_parent.`id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: student_assessment_appointment has a missing reference';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `student_assessment_question_answer` child
    LEFT JOIN `assessment` assessment_parent ON assessment_parent.`id` = child.`assessment_id`
    LEFT JOIN `user` student_parent ON student_parent.`id` = child.`student_id`
    WHERE child.`assessment_id` IS NULL
       OR child.`student_id` IS NULL
       OR assessment_parent.`id` IS NULL
       OR student_parent.`id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: student_assessment_question_answer has a missing reference';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `student_assessment_record` child
    LEFT JOIN `assessment` assessment_parent ON assessment_parent.`id` = child.`assessment_id`
    LEFT JOIN `user` student_parent ON student_parent.`id` = child.`student_id`
    WHERE assessment_parent.`id` IS NULL OR student_parent.`id` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V5 blocked: student_assessment_record has a missing reference';
  END IF;
END//
DELIMITER ;

CALL flyway_precheck_v5_references();
DROP PROCEDURE flyway_precheck_v5_references;

ALTER TABLE `assessment`
  MODIFY `create_id` INT NOT NULL,
  ADD CONSTRAINT `fk_assessment_creator`
    FOREIGN KEY (`create_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT;

ALTER TABLE `assessment_invite`
  MODIFY `inviter_teacher_id` INT NOT NULL,
  ADD KEY `idx_ai_inviter_teacher_id` (`inviter_teacher_id`),
  ADD CONSTRAINT `fk_invite_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_invite_invitee_teacher`
    FOREIGN KEY (`invitee_teacher_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
  ADD CONSTRAINT `fk_invite_inviter_teacher`
    FOREIGN KEY (`inviter_teacher_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT;

ALTER TABLE `assessment_student_relation`
  MODIFY `relation_type` INT NOT NULL,
  ADD CONSTRAINT `fk_relation_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_relation_student`
    FOREIGN KEY (`student_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT;

ALTER TABLE `assessment_appointment_setting`
  MODIFY `assessment_id` INT NOT NULL,
  ADD CONSTRAINT `fk_appointment_setting_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE;

ALTER TABLE `student_assessment_appointment`
  ADD CONSTRAINT `fk_student_appointment_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_student_appointment_student`
    FOREIGN KEY (`student_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT;

ALTER TABLE `student_assessment_question_answer`
  MODIFY `assessment_id` INT NOT NULL,
  MODIFY `student_id` INT NOT NULL,
  ADD CONSTRAINT `fk_answer_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_answer_student`
    FOREIGN KEY (`student_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT;

ALTER TABLE `student_assessment_record`
  ADD CONSTRAINT `fk_record_assessment`
    FOREIGN KEY (`assessment_id`) REFERENCES `assessment` (`id`) ON DELETE CASCADE,
  ADD CONSTRAINT `fk_record_student`
    FOREIGN KEY (`student_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT;
