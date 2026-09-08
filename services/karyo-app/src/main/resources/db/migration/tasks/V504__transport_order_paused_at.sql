-- PT18: orthogonal transport-order pause, mirroring goods-receipt's B7 model
-- (V422__gr_operator_pause_scalars.sql). state NEVER moves on pause/resume, so
-- resume restores the task exactly where it was (myWMS's PAUSE state jump lost
-- STARTED on resume — deliberately not adopted here). Pausable window is
-- CREATED/RELEASED/RESERVED/STARTED (TaskService.pause) -- anytime pre-terminal.
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS paused_at TIMESTAMPTZ;
