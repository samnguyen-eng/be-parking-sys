-- V2: Seed 80 chỗ đậu xe (A01-A10, B01-B10, ..., H01-H10)
INSERT INTO parking_spaces (space_number, status)
SELECT
    CONCAT(row_letter, LPAD(col_num::TEXT, 2, '0')),
    'AVAILABLE'
FROM
    (VALUES ('A'), ('B'), ('C'), ('D'), ('E'), ('F'), ('G'), ('H')) AS rows(row_letter),
    generate_series(1, 10) AS col_num
ON CONFLICT (space_number) DO NOTHING;
