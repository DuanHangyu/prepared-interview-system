-- Constrain the persisted authorization domain used throughout the application.
-- Invalid legacy rows are left untouched so an operator can repair them and
-- safely retry the migration.
DROP PROCEDURE IF EXISTS flyway_precheck_v6_user_roles;
DELIMITER //
CREATE PROCEDURE flyway_precheck_v6_user_roles()
BEGIN
  IF EXISTS (
    SELECT 1 FROM `user`
    WHERE `type` IS NULL OR `type` NOT IN (0, 1, 2)
    LIMIT 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V6 blocked: user table contains invalid role values';
  END IF;
END//
DELIMITER ;

CALL flyway_precheck_v6_user_roles();
DROP PROCEDURE flyway_precheck_v6_user_roles;

ALTER TABLE `user`
  MODIFY COLUMN `type` INT NOT NULL,
  ADD CONSTRAINT `chk_user_role` CHECK (`type` IN (0, 1, 2));
