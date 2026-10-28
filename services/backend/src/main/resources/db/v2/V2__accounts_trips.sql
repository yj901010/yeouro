CREATE SCHEMA IF NOT EXISTS app;
CREATE TABLE app.app_users(id uuid PRIMARY KEY,display_name varchar(40) NOT NULL CHECK(length(trim(display_name))>0),status text NOT NULL CHECK(status IN ('ACTIVE','DELETION_PENDING')),default_time_zone text NOT NULL,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE app.trips(
 id uuid PRIMARY KEY,owner_user_id uuid NOT NULL REFERENCES app.app_users(id),title varchar(120),
 destination_name varchar(160) NOT NULL CHECK(length(trim(destination_name))>0),destination_latitude double precision,destination_longitude double precision,
 deadline_at timestamptz,arrival_basis text NOT NULL CHECK(arrival_basis IN ('PERSON','VEHICLE')),planned_stay_minutes integer CHECK(planned_stay_minutes BETWEEN 1 AND 10080),time_zone text NOT NULL,
 status text NOT NULL CHECK(status IN ('PLANNED','ACTIVE','ENDED','CANCELLED','EXPIRED')),phase text CHECK(phase IN ('DRIVING','PARKED','WALKING','AT_DESTINATION','RETURNING')),
 starts_at timestamptz,started_at timestamptz,ended_at timestamptz,expires_at timestamptz NOT NULL,
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>=0),plan_revision bigint NOT NULL DEFAULT 1 CHECK(plan_revision>=0),version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT now(),updated_at timestamptz NOT NULL DEFAULT now(),
 CHECK((destination_latitude IS NULL AND destination_longitude IS NULL) OR (destination_latitude IS NOT NULL AND destination_longitude IS NOT NULL AND destination_latitude BETWEEN -90 AND 90 AND destination_longitude BETWEEN -180 AND 180))
);
CREATE INDEX trips_owner ON app.trips(owner_user_id,created_at DESC);
CREATE INDEX trips_expiry ON app.trips(expires_at) WHERE status IN ('PLANNED','ACTIVE');
CREATE TABLE app.trip_participants(
 id uuid PRIMARY KEY,trip_id uuid NOT NULL REFERENCES app.trips(id),user_id uuid NOT NULL REFERENCES app.app_users(id),role text NOT NULL CHECK(role IN ('DRIVER','COMPANION')),status text NOT NULL CHECK(status IN ('ACTIVE','LEFT','REMOVED')),
 display_name varchar(40) NOT NULL CHECK(length(trim(display_name))>0),ready boolean NOT NULL DEFAULT false,ready_updated_at timestamptz,joined_at timestamptz NOT NULL DEFAULT now(),left_at timestamptz,
 UNIQUE(trip_id,id),UNIQUE(trip_id,user_id)
);
CREATE UNIQUE INDEX one_active_driver ON app.trip_participants(trip_id) WHERE role='DRIVER' AND status='ACTIVE';
CREATE INDEX participants_user ON app.trip_participants(user_id,status,joined_at DESC);
CREATE TABLE app.idempotency_records(
 principal text NOT NULL,route text NOT NULL,idempotency_key varchar(128) NOT NULL,request_hash bytea NOT NULL,response_json text,secret_response boolean NOT NULL,expires_at timestamptz NOT NULL,
 PRIMARY KEY(principal,route,idempotency_key),CHECK(NOT secret_response OR response_json IS NULL)
);
CREATE INDEX idempotency_expiry ON app.idempotency_records(expires_at);
