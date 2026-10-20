-- eezo migration 2
-- api_token
-- fingerprint: ce3123415d14c197
-- GENERATED. Do not edit; change the model and re-freeze.

alter table "user" add column "token" text;
