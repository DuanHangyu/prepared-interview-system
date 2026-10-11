-- Appointment lookup assumes that one assessment has at most one setting for
-- a given time. Stop for operator-led cleanup if legacy rows violate that
-- invariant; never guess which capacity or location should win.
DROP PROCEDURE IF EXISTS flyway_precheck_v11_appointment_periods;
DELIMITER //
CREATE PROCEDURE flyway_precheck_v11_appointment_periods()
BEGIN
  IF EXISTS (
    SELECT 1 FROM `assessment_appointment_setting`
    WHERE `time_period` IS NULL
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V11 blocked: assessment appointment period is null';
  END IF;

  IF EXISTS (
    SELECT 1 FROM `assessment_appointment_setting`
    GROUP BY `assessment_id`, `time_period` HAVING COUNT(*) > 1
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V11 blocked: duplicate assessment appointment periods';
  END IF;
END//
DELIMITER ;

CALL flyway_precheck_v11_appointment_periods();
DROP PROCEDURE flyway_precheck_v11_appointment_periods;

ALTER TABLE `assessment_appointment_setting`
  MODIFY `time_period` DATETIME NOT NULL,
  ADD UNIQUE KEY `uk_appointment_setting_assessment_time` (`assessment_id`, `time_period`);
