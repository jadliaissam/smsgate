-- name: GetPlan :one
SELECT * FROM plans WHERE id = $1;

-- name: GetUserPlan :one
SELECT p.* FROM plans p JOIN users u ON u.plan_id = p.id WHERE u.id = $1;
