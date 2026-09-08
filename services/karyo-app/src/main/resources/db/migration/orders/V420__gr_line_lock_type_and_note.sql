-- B5: arbitrary lock type + note at receipt; qa_hold becomes DERIVED (lockType != null).
-- lock_type holds an inventory LockType code (receive subset {1,103,202,203}); NULL = no lock.
-- note is the authoritative full operator note (the journal hop truncates visibly to 50).
-- The old boolean is backfilled into lock_type (QUALITY_FAULT 103) then DROPPED so the
-- derived value and a stored flag can never disagree (same doctrine as D4 pickedAmount).
ALTER TABLE goods_receipt_lines ADD COLUMN lock_type INT;
ALTER TABLE goods_receipt_lines ADD COLUMN note VARCHAR(255);

UPDATE goods_receipt_lines SET lock_type = 103 WHERE qa_hold;

ALTER TABLE goods_receipt_lines DROP COLUMN qa_hold;
