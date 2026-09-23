CREATE TABLE resource (
 id varchar(80) PRIMARY KEY, kind varchar(30) NOT NULL, project varchar(80) NOT NULL,
 version bigint NOT NULL DEFAULT 1, body jsonb NOT NULL,
 observed jsonb NOT NULL DEFAULT '{}', observed_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), deleted boolean NOT NULL DEFAULT false
);
CREATE INDEX resource_project_kind ON resource(project,kind,created_at);
CREATE TABLE revision (
 workload_id varchar(80) NOT NULL REFERENCES resource(id), version bigint NOT NULL,
 body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(workload_id,version)
);
CREATE TABLE operation (
 id varchar(80) PRIMARY KEY, project varchar(80) NOT NULL, target_id varchar(80) NOT NULL REFERENCES resource(id),
 action varchar(40) NOT NULL, generation bigint NOT NULL, status varchar(20) NOT NULL DEFAULT 'pending',
 stage varchar(60) NOT NULL DEFAULT 'accepted', error_code varchar(80),
 lease_owner varchar(80), lease_until timestamptz, next_run timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), attempts integer NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX operation_active_target ON operation(target_id) WHERE status IN ('pending','running','blocked','unknown');
CREATE TABLE idempotency (
 caller varchar(120) NOT NULL, method varchar(10) NOT NULL, path varchar(240) NOT NULL,
 key varchar(128) NOT NULL, request_hash varchar(64) NOT NULL, response jsonb NOT NULL,
 expires_at timestamptz NOT NULL DEFAULT now()+interval '7 days', PRIMARY KEY(caller,method,path,key)
);
CREATE TABLE audit_event (
 id bigserial PRIMARY KEY, caller varchar(120) NOT NULL, project varchar(80) NOT NULL,
 request_id varchar(80) NOT NULL, action varchar(80) NOT NULL, target_id varchar(80), result varchar(40) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now()
);
