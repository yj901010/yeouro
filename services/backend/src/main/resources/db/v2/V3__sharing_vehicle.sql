ALTER TABLE app.trip_participants ADD COLUMN consent_version bigint NOT NULL DEFAULT 0 CHECK(consent_version>=0);
CREATE TABLE app.sharing_sessions (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id), generation bigint NOT NULL CHECK(generation>0),
 status text NOT NULL CHECK(status IN ('ACTIVE','REVOKED','EXPIRED','ENDED')), started_by_participant_id uuid NOT NULL,
 started_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, revoked_at timestamptz, revocation_reason text,
 UNIQUE(trip_id,id), UNIQUE(trip_id,generation), FOREIGN KEY(trip_id,started_by_participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE UNIQUE INDEX one_active_sharing ON app.sharing_sessions(trip_id) WHERE status='ACTIVE';
CREATE TABLE app.participant_consents (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL, sharing_session_id uuid NOT NULL, participant_id uuid NOT NULL,
 scope text NOT NULL CHECK(scope IN ('LOCATION_PUBLISH','LOCATION_VIEW','TRIP_COLLABORATION')),
 policy_version varchar(40) NOT NULL, granted_at timestamptz NOT NULL, revoked_at timestamptz,
 FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE UNIQUE INDEX one_active_consent ON app.participant_consents(sharing_session_id,participant_id,scope) WHERE revoked_at IS NULL;
CREATE TABLE app.trip_invitations (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL, sharing_session_id uuid NOT NULL, created_by_participant_id uuid NOT NULL,
 token_hash bytea NOT NULL UNIQUE, offered_role text NOT NULL CHECK(offered_role IN ('DRIVER','COMPANION')),
 expires_at timestamptz NOT NULL, consumed_at timestamptz, consumed_by_user_id uuid REFERENCES app.app_users(id), revoked_at timestamptz,
 FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,created_by_participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE TABLE app.vehicle_pairings (
 id uuid PRIMARY KEY, trip_id uuid, sharing_session_id uuid, driver_participant_id uuid, installation_id uuid NOT NULL,
 challenge_hash bytea NOT NULL UNIQUE, poll_secret_hash bytea NOT NULL UNIQUE,
 status text NOT NULL CHECK(status IN ('PENDING','APPROVED','CLAIMED','REVOKED','EXPIRED')),
 scopes text NOT NULL DEFAULT '', expires_at timestamptz NOT NULL, approved_at timestamptz, revoked_at timestamptz,
 UNIQUE(trip_id,id), FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,driver_participant_id) REFERENCES app.trip_participants(trip_id,id),
 CHECK((status='PENDING' AND trip_id IS NULL AND sharing_session_id IS NULL AND driver_participant_id IS NULL) OR status IN ('REVOKED','EXPIRED') OR (status IN ('APPROVED','CLAIMED') AND trip_id IS NOT NULL AND sharing_session_id IS NOT NULL AND driver_participant_id IS NOT NULL))
);
CREATE TABLE app.vehicle_credentials (
 id uuid PRIMARY KEY, pairing_id uuid NOT NULL UNIQUE REFERENCES app.vehicle_pairings(id),trip_id uuid NOT NULL,
 sharing_session_id uuid NOT NULL, driver_participant_id uuid NOT NULL, token_hash bytea NOT NULL UNIQUE,
 scopes text NOT NULL, expires_at timestamptz NOT NULL,revoked_at timestamptz,last_used_at timestamptz,
 FOREIGN KEY(trip_id,pairing_id) REFERENCES app.vehicle_pairings(trip_id,id),
 FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,driver_participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE TABLE app.trip_latest_locations (
 trip_id uuid PRIMARY KEY REFERENCES app.trips(id), sharing_session_id uuid NOT NULL,publisher_participant_id uuid NOT NULL,vehicle_pairing_id uuid,
 latitude double precision NOT NULL CHECK(latitude BETWEEN -90 AND 90),longitude double precision NOT NULL CHECK(longitude BETWEEN -180 AND 180),
 accuracy_meters double precision NOT NULL CHECK(accuracy_meters BETWEEN 0 AND 10000),observed_at timestamptz NOT NULL,received_at timestamptz NOT NULL,
 source text NOT NULL CHECK(source IN ('PHONE','VEHICLE','SIMULATED')),publisher_sequence bigint,observation_id uuid NOT NULL,request_hash bytea NOT NULL,
 FOREIGN KEY(trip_id,sharing_session_id) REFERENCES app.sharing_sessions(trip_id,id),
 FOREIGN KEY(trip_id,publisher_participant_id) REFERENCES app.trip_participants(trip_id,id),
 FOREIGN KEY(trip_id,vehicle_pairing_id) REFERENCES app.vehicle_pairings(trip_id,id)
);
CREATE INDEX invitations_expiry ON app.trip_invitations(expires_at);
CREATE INDEX credentials_expiry ON app.vehicle_credentials(expires_at);
