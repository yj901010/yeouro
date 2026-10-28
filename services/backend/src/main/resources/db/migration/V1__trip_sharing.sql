CREATE TABLE trips (
  id UUID PRIMARY KEY, lock_version BIGINT NOT NULL, revision BIGINT NOT NULL,
  destination_name VARCHAR(120) NOT NULL, destination_latitude DOUBLE PRECISION,
  destination_longitude DOUBLE PRECISION, status VARCHAR(20) NOT NULL,
  sharing_enabled BOOLEAN NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  location_latitude DOUBLE PRECISION, location_longitude DOUBLE PRECISION,
  location_accuracy_meters DOUBLE PRECISION, location_observed_at TIMESTAMP WITH TIME ZONE,
  location_source VARCHAR(20)
);
CREATE INDEX trips_expiry ON trips(expires_at);
CREATE TABLE participants (
  id UUID PRIMARY KEY, trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  display_name VARCHAR(40) NOT NULL, role VARCHAR(20) NOT NULL,
  ready BOOLEAN NOT NULL, active BOOLEAN NOT NULL
);
CREATE INDEX participants_trip ON participants(trip_id);
CREATE TABLE access_tokens (
  hash VARCHAR(64) PRIMARY KEY, trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  participant_id UUID REFERENCES participants(id) ON DELETE CASCADE, role VARCHAR(20) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked BOOLEAN NOT NULL
);
CREATE INDEX tokens_trip ON access_tokens(trip_id);
CREATE TABLE invitations (
  code_hash VARCHAR(64) PRIMARY KEY, trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  consumed BOOLEAN NOT NULL, revoked BOOLEAN NOT NULL
);
CREATE INDEX invitations_trip ON invitations(trip_id);
CREATE TABLE proposals (
  id UUID PRIMARY KEY, trip_id UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  participant_id UUID NOT NULL REFERENCES participants(id) ON DELETE CASCADE,
  request_id UUID NOT NULL, name VARCHAR(120) NOT NULL,
  latitude DOUBLE PRECISION, longitude DOUBLE PRECISION, status VARCHAR(24) NOT NULL,
  revision BIGINT NOT NULL, result_reason VARCHAR(200),
  UNIQUE(participant_id, request_id)
);
CREATE INDEX proposals_trip ON proposals(trip_id);
