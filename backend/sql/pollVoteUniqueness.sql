-- Run once, with the backend stopped, before deploying the poll vote uniqueness fix.
-- Existing duplicate votes cause this transaction to fail without deleting any votes.
BEGIN;

LOCK TABLE poll_votes, poll_options IN ACCESS EXCLUSIVE MODE;

ALTER TABLE poll_votes ADD COLUMN poll_id BIGINT;

UPDATE poll_votes AS vote
SET poll_id = option.poll_id
FROM poll_options AS option
WHERE vote.poll_option_id = option.id;

ALTER TABLE poll_votes ALTER COLUMN poll_id SET NOT NULL;
ALTER TABLE poll_votes
    ADD CONSTRAINT fk_poll_votes_poll FOREIGN KEY (poll_id) REFERENCES polls (id),
    ADD CONSTRAINT uk_poll_votes_poll_user UNIQUE (poll_id, user_id);

COMMIT;
