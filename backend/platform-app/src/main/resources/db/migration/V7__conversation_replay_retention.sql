-- Public replay is disposable; canonical messages and execution ledgers are not.
ALTER TABLE pf.conversation ADD COLUMN replay_pruned_through bigint NOT NULL DEFAULT 0;
UPDATE pf.conversation c SET replay_pruned_through = COALESCE(
    (SELECT min(e.seq)-1 FROM pf.platform_event e WHERE e.conversation_id=c.id), c.next_seq);
ALTER TABLE pf.conversation ADD CONSTRAINT conversation_replay_bounds
    CHECK (replay_pruned_through >= 0 AND replay_pruned_through <= next_seq);
CREATE INDEX platform_event_retention_candidates ON pf.platform_event(created_at,conversation_id,seq);

-- Fixed policy, one locked conversation and at most 1000 rows per transaction.
CREATE FUNCTION pf.collect_expired_replay(candidate uuid) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pf AS $$
DECLARE
    target uuid;
    previous bigint;
    through_seq bigint;
    cutoff timestamptz := transaction_timestamp() - interval '30 days';
    item record;
    deleted integer;
BEGIN
    SELECT c.id,c.replay_pruned_through INTO target,previous
    FROM pf.conversation c JOIN pf.platform_event e
      ON e.conversation_id=c.id AND e.seq=c.replay_pruned_through+1
    WHERE c.id=candidate AND e.created_at < cutoff
    ORDER BY e.created_at,c.id
    LIMIT 1 FOR UPDATE OF c SKIP LOCKED;
    IF NOT FOUND THEN RETURN 0; END IF;
    through_seq := previous;
    FOR item IN SELECT seq,created_at FROM pf.platform_event
        WHERE conversation_id=target AND seq>previous ORDER BY seq LIMIT 1000
    LOOP
        EXIT WHEN item.seq <> through_seq+1 OR item.created_at >= cutoff;
        through_seq := item.seq;
    END LOOP;
    DELETE FROM pf.platform_event WHERE conversation_id=target AND seq>previous AND seq<=through_seq;
    GET DIAGNOSTICS deleted = ROW_COUNT;
    IF deleted <> through_seq-previous THEN RAISE EXCEPTION 'Non-contiguous replay prefix'; END IF;
    UPDATE pf.conversation SET replay_pruned_through=through_seq WHERE id=target;
    RETURN deleted;
END $$;
REVOKE ALL ON FUNCTION pf.collect_expired_replay(uuid) FROM PUBLIC;
