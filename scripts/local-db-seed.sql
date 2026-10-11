-- ============================================================================
-- Optional seed data for a local development DB after Flyway migration.
-- Passwords are BCrypt hashes for the documented local-only credentials.
-- Roles: STUDENT=0, TEACHER=1, ADMIN=2.
-- Apply on the `defense_test` database after the backend has started once.
-- ============================================================================

SET NAMES utf8mb4;

-- ---------- 3 role accounts ----------
-- admin / admin123
INSERT INTO `user` (`name`, `account`, `password`, `phone`, `type`, `school_class`, `create_time`, `update_time`)
VALUES ('管理员', 'admin', '$2a$12$q6GI5Ejzs.fgWRQIfE3PxupNP2URaDKtUjxzPqkaIyGjzKyUmBiAq', '13800000000', 2, NULL, NOW(), NOW());

-- teacher / teacher123
INSERT INTO `user` (`name`, `account`, `password`, `phone`, `type`, `school_class`, `create_time`, `update_time`)
VALUES ('张老师', 'teacher', '$2a$12$jCEt1L1mKAeqxE.FHEw3wulr4zb6P5kNU7p00dZO4uOkqIryyrAxi', '13800000001', 1, NULL, NOW(), NOW());

-- student / student123
INSERT INTO `user` (`name`, `account`, `password`, `phone`, `type`, `school_class`, `create_time`, `update_time`)
VALUES ('李同学', 'student', '$2a$12$VdG3.VETsrpWBK9P2xets.Krc69GNIOltdKE1oS25AFVETRGuJ2t6', '13800000002', 0, '计算机2101', NOW(), NOW());

-- ---------- project types (dashboards reference these) ----------
INSERT INTO `project_type` (`project_type_key`, `project_type`) VALUES
  ('course_project', '课程项目'),
  ('thesis_defense', '毕业答辩'),
  ('capability_test', '能力测评');
