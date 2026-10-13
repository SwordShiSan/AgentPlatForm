CREATE SCHEMA IF NOT EXISTS pf;
CREATE SCHEMA IF NOT EXISTS pf_runtime;
CREATE TABLE pf_runtime.state_slot (
    space_id uuid NOT NULL,
    user_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    state_key text NOT NULL CHECK (length(state_key) BETWEEN 1 AND 256),
    version bigint NOT NULL CHECK (version >= 1),
    framework_version text NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (space_id,user_id,conversation_id,state_key)
);
CREATE TABLE pf.baseline_version (
    id integer PRIMARY KEY CHECK (id = 1),
    schema_version integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO pf.baseline_version(id,schema_version) VALUES (1,1);
