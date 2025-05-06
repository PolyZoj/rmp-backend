CREATE DATABASE IF NOT EXISTS default;

DROP TABLE  IF EXISTS default.user_stats;

CREATE TABLE IF NOT EXISTS default.user_stats
(
    id String,
    user_id String,
    event_type String,
    value Float64,
    timestamp String
)
ENGINE = ReplacingMergeTree()
ORDER BY (id);