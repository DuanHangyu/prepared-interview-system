-- Missing historical provenance remains unverified; never synthesize a model input/result.
ALTER TABLE prepared_processing_result ADD COLUMN input_json MEDIUMTEXT NULL,
    ADD CONSTRAINT ck_prepared_result_input_json CHECK(input_json IS NULL OR JSON_VALID(input_json));
-- First whole-attempt result completion is a retention anchor, not a new exam deadline.
ALTER TABLE prepared_attempt ADD COLUMN first_results_completed_at DATETIME(3) NULL;
ALTER TABLE prepared_result_command ADD COLUMN request_json TEXT NULL,
    ADD CONSTRAINT ck_prepared_result_command_request CHECK(request_json IS NULL OR JSON_VALID(request_json));
