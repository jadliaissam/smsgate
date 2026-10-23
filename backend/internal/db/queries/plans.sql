-- name: GetPlan :one
SELECT * FROM plans WHERE id = $1;
