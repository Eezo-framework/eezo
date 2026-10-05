-- eezo migration 1
-- initial_schema
-- fingerprint: 26fa790fa77dfe1f
-- GENERATED. Do not edit; change the model and re-freeze.

create table "order" (
  "at" timestamptz not null,
  "id" uuid primary key,
  "name" text not null,
  "paid" boolean not null,
  "price" integer not null,
  "product" uuid not null
);

create table "product" (
  "id" uuid primary key,
  "name" text not null,
  "price" integer not null
);

create table "user" (
  "email" text not null,
  "id" uuid primary key,
  "password" text not null
);

create unique index "uq_user_email" on "user" ("email");
