-- G30: scope idempotency keys per show. A key guards one operation on one
-- show: same key + same body on a different show is an independent
-- operation, not a reuse conflict. Same-show semantics are unchanged.
ALTER TABLE idempotency_keys ADD COLUMN show_id UUID;

UPDATE idempotency_keys k
SET show_id = r.show_id
FROM reservations r
WHERE r.id = k.reservation_id;

DELETE FROM idempotency_keys WHERE show_id IS NULL;

ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ALTER COLUMN show_id SET NOT NULL;
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_pkey PRIMARY KEY (user_id, show_id, idempotency_key);
ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_show_fk FOREIGN KEY (show_id) REFERENCES shows (id) ON DELETE CASCADE;
