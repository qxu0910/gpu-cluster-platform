CREATE TABLE node_snapshot (
 project varchar(80) NOT NULL,
 cluster_id varchar(80) NOT NULL,
 nodes jsonb NOT NULL DEFAULT '[]',
 observed_at timestamptz,
 error_code varchar(80),
 PRIMARY KEY(project,cluster_id)
);
