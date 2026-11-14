CREATE TABLE app_registration_gate (
    gate_id SMALLINT PRIMARY KEY CHECK (gate_id = 1),
    initial_signup_complete BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO app_registration_gate (gate_id, initial_signup_complete)
VALUES (1, FALSE);
