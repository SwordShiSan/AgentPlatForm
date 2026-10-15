CREATE TABLE pf.app_user (
    id uuid PRIMARY KEY,
    username_norm varchar(64) NOT NULL UNIQUE,
    password_hash text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE','DISABLED','DELETED')),
    platform_admin boolean NOT NULL DEFAULT false,
    authz_revision bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE pf.space (
    id uuid PRIMARY KEY,
    type text NOT NULL CHECK (type IN ('PERSONAL','ORGANIZATION')),
    owner_user_id uuid NOT NULL REFERENCES pf.app_user(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX one_personal_space ON pf.space(owner_user_id) WHERE type='PERSONAL';
CREATE TABLE pf.membership (
    space_id uuid NOT NULL REFERENCES pf.space(id),
    user_id uuid NOT NULL REFERENCES pf.app_user(id),
    role text NOT NULL CHECK (role IN ('OWNER','ADMIN','MEMBER','VIEWER')),
    status text NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    version bigint NOT NULL DEFAULT 1,
    PRIMARY KEY(space_id,user_id)
);
CREATE TABLE pf.space_storage (
    space_id uuid PRIMARY KEY REFERENCES pf.space(id),
    status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','FROZEN','DELETING','DELETED')),
    policy_version integer NOT NULL DEFAULT 1,
    object_limit_bytes bigint NOT NULL DEFAULT 10737418240 CHECK(object_limit_bytes>=0),
    reserved_bytes bigint NOT NULL DEFAULT 0 CHECK(reserved_bytes>=0),
    committed_bytes bigint NOT NULL DEFAULT 0 CHECK(committed_bytes>=0),
    version bigint NOT NULL DEFAULT 1,
    CHECK(reserved_bytes+committed_bytes<=object_limit_bytes)
);
CREATE TABLE pf.identity_operation (
    request_id uuid PRIMARY KEY,
    input_hash text NOT NULL,
    user_id uuid NOT NULL REFERENCES pf.app_user(id),
    bootstrap boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX bootstrap_once ON pf.identity_operation(bootstrap) WHERE bootstrap;
CREATE TABLE pf.auth_session (
    id uuid PRIMARY KEY,
    token_hash varchar(64) NOT NULL UNIQUE,
    csrf_hash varchar(64) NOT NULL,
    user_id uuid REFERENCES pf.app_user(id),
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX auth_session_user ON pf.auth_session(user_id);
CREATE INDEX auth_session_expiry ON pf.auth_session(expires_at);
CREATE TABLE pf.auth_rate (
    bucket text PRIMARY KEY,
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    locked_until timestamptz
);
