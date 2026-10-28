CREATE TABLE app.place_proposals (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id), sharing_session_id uuid NOT NULL,
 proposer_participant_id uuid NOT NULL, kind text NOT NULL CHECK(kind IN ('DESTINATION','STOP','PARKING')),
 place_name varchar(120) NOT NULL CHECK(length(trim(place_name)) > 0), latitude double precision, longitude double precision,
 decision_status text NOT NULL DEFAULT 'PENDING' CHECK(decision_status IN ('PENDING','ACCEPTED','DISMISSED','CANCELLED')),
 decided_by_participant_id uuid, decided_at timestamptz, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(trip_id,id), FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,proposer_participant_id) REFERENCES app.trip_participants(trip_id,id),
 FOREIGN KEY(trip_id,decided_by_participant_id) REFERENCES app.trip_participants(trip_id,id),
 CHECK((latitude IS NULL AND longitude IS NULL) OR (latitude IS NOT NULL AND longitude IS NOT NULL AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180))
);
CREATE INDEX proposal_pending ON app.place_proposals(trip_id,decision_status,created_at);
CREATE TABLE app.navigation_commands (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id), sharing_session_id uuid NOT NULL,
 proposal_id uuid NOT NULL UNIQUE, target_pairing_id uuid, requested_by_participant_id uuid NOT NULL,
 command_type text NOT NULL CHECK(command_type IN ('SET_DESTINATION','ADD_STOP','SELECT_PARKING')),
 target_name varchar(120) NOT NULL, target_latitude double precision, target_longitude double precision,
 status text NOT NULL CHECK(status IN ('REQUESTED','CLAIMED','APPLIED','FAILED','DEMO_APPLIED','CANCELLED','EXPIRED','OUTCOME_UNKNOWN')),
 integration_mode text NOT NULL CHECK(integration_mode IN ('DEMO','PLEOS')),
 requested_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, claimed_at timestamptz, completed_at timestamptz,
 result_code varchar(80), version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(trip_id,id), FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,proposal_id) REFERENCES app.place_proposals(trip_id,id),
 FOREIGN KEY(trip_id,target_pairing_id) REFERENCES app.vehicle_pairings(trip_id,id),
 FOREIGN KEY(trip_id,requested_by_participant_id) REFERENCES app.trip_participants(trip_id,id),
 CHECK((target_latitude IS NULL AND target_longitude IS NULL) OR (target_latitude IS NOT NULL AND target_longitude IS NOT NULL AND target_latitude BETWEEN -90 AND 90 AND target_longitude BETWEEN -180 AND 180)),
 CHECK(status NOT IN ('REQUESTED','CLAIMED') OR (target_pairing_id IS NOT NULL AND target_latitude IS NOT NULL AND target_longitude IS NOT NULL))
);
CREATE UNIQUE INDEX one_live_navigation_command ON app.navigation_commands(trip_id) WHERE status IN ('REQUESTED','CLAIMED','OUTCOME_UNKNOWN');
