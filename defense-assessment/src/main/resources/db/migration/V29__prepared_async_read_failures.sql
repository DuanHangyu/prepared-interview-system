-- GET and result-read failures are not new paid attempts. Persist their own bounded counters.
ALTER TABLE prepared_async_model_task
    ADD COLUMN query_failures INT NOT NULL DEFAULT 0,
    ADD COLUMN result_failures INT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_prepared_async_read_failures CHECK (query_failures BETWEEN 0 AND 3 AND result_failures BETWEEN 0 AND 3);
