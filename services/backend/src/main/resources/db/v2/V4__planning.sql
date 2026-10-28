CREATE TABLE app.trip_stops (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id) ON DELETE CASCADE,
 sequence_no integer NOT NULL CHECK(sequence_no>=0), place_json text NOT NULL,
 stay_minutes integer NOT NULL CHECK(stay_minutes BETWEEN 0 AND 10080),
 walk_to_place_seconds integer CHECK(walk_to_place_seconds>=0), return_walk_seconds integer CHECK(return_walk_seconds>=0),
 UNIQUE(trip_id,sequence_no)
);
CREATE TABLE app.comparison_snapshots (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id) ON DELETE CASCADE,
 requested_by_participant_id uuid NOT NULL, trip_plan_revision bigint NOT NULL,
 mode varchar(8) NOT NULL CHECK(mode IN ('DEMO','REAL')), calculated_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
 snapshot_json text NOT NULL,
 FOREIGN KEY(trip_id,requested_by_participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE INDEX comparison_trip_time ON app.comparison_snapshots(trip_id,calculated_at);
CREATE TABLE app.trip_milestones (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id) ON DELETE CASCADE,
 type varchar(40) NOT NULL CHECK(type IN ('VEHICLE_ARRIVED','PARKING_CONFIRMED','PERSON_ARRIVED','RETURNED_TO_CAR')),
 occurred_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL,
 reported_by_participant_id uuid NOT NULL, source varchar(30) NOT NULL CHECK(source IN ('USER_CONFIRMED','SDK_EVENT','SIMULATED')),
 place_json text,
 FOREIGN KEY(trip_id,reported_by_participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE TABLE app.trip_phase_events (
 id uuid PRIMARY KEY, trip_id uuid NOT NULL REFERENCES app.trips(id) ON DELETE CASCADE,
 phase varchar(30) NOT NULL CHECK(phase IN ('DRIVING','PARKED','WALKING','AT_DESTINATION','RETURNING')),
 observed_at timestamptz NOT NULL, recorded_at timestamptz NOT NULL, source varchar(30) NOT NULL,
 reported_by_participant_id uuid NOT NULL, user_confirmed boolean NOT NULL,
 FOREIGN KEY(trip_id,reported_by_participant_id) REFERENCES app.trip_participants(trip_id,id)
);
CREATE TABLE app.return_reminder_plans (
 id uuid PRIMARY KEY, trip_id uuid UNIQUE NOT NULL REFERENCES app.trips(id) ON DELETE CASCADE,
 anchor varchar(40) NOT NULL CHECK(anchor IN ('PARKING_CONFIRMED','PERSON_ARRIVAL_CONFIRMED')),
 stay_minutes integer NOT NULL CHECK(stay_minutes BETWEEN 1 AND 10080), lead_minutes integer NOT NULL CHECK(lead_minutes BETWEEN 0 AND 10080),
 reminder_basis varchar(30) NOT NULL CHECK(reminder_basis IN ('STAY_END','RETURN_TO_CAR')), return_walk_seconds integer CHECK(return_walk_seconds>=0),
 remind_at timestamptz, status varchar(30) NOT NULL CHECK(status IN ('DISABLED','WAITING_FOR_ANCHOR','PLANNED','CANCELLED')), version bigint NOT NULL CHECK(version>=0), UNIQUE(trip_id,id)
);
CREATE TABLE app.reminder_client_statuses (
 trip_id uuid NOT NULL, plan_id uuid NOT NULL,
 participant_id uuid NOT NULL, installation_id uuid NOT NULL,
 plan_version bigint NOT NULL CHECK(plan_version>=0), status varchar(30) NOT NULL CHECK(status IN ('SCHEDULED','PERMISSION_DENIED','CANCELLED','ERROR')),
 observed_at timestamptz NOT NULL, PRIMARY KEY(plan_id,participant_id,installation_id),
 FOREIGN KEY(trip_id,plan_id) REFERENCES app.return_reminder_plans(trip_id,id) ON DELETE CASCADE,
 FOREIGN KEY(trip_id,participant_id) REFERENCES app.trip_participants(trip_id,id)
);
