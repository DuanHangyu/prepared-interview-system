-- Older application versions used zero as the placeholder for "not reviewed".
-- New writes use NULL so that an explicit teacher score of zero remains meaningful.
UPDATE `student_assessment_record`
SET `check_score` = NULL
WHERE `check_score` = 0;
