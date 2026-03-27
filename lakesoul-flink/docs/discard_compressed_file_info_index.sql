
create table if not exists discard_compressed_file_info
(
    file_path text,
    table_path text,
    partition_desc text,
    timestamp bigint DEFAULT (date_part('epoch'::text, now()) * (1000)::double precision),
    t_date date,
    PRIMARY KEY (file_path)
);


CREATE INDEX CONCURRENTLY IF NOT EXISTS discard_compressed_file_info_ts_fp_idx
ON discard_compressed_file_info (timestamp, file_path);
