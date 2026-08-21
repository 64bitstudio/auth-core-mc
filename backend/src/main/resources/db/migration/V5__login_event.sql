-- Ticket 015: registro de eventos de login — base para las métricas de
-- uso del panel de administración (ticket 016). Sin particionamiento
-- (volumen bajo/moderado esperado, decisión tomada en la fase de
-- definición). New file, not an edit to V1-V4 — Flyway migrations are
-- immutable once applied.

CREATE TABLE login_event (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    user_id      UUID REFERENCES app_user (id),
    provider     TEXT NOT NULL,
    outcome      TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    latency_ms   INTEGER NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX login_event_tenant_occurred_at_idx ON login_event (tenant_id, occurred_at);
