-- eezo migration 1
-- initial
-- fingerprint: 33f8c49bb0fe2b3a
-- GENERATED. Do not edit; change the model and re-freeze.

create table "author" (
  "country" text,
  "id" uuid primary key,
  "mentor_id" uuid,
  "name" text not null
);

create table "book" (
  "author_id" uuid not null,
  "format" text not null,
  "id" uuid primary key,
  "isbn" text,
  "published_by_id" uuid,
  "published_on" date,
  "title" text not null check (length(title) <= 100)
);

create table "publishing_house" (
  "id" uuid primary key,
  "location" text not null,
  "name" text not null
);

alter table "author" add constraint "fk_author_mentor_id" foreign key ("mentor_id") references "author" ("id");

alter table "book" add constraint "fk_book_author_id" foreign key ("author_id") references "author" ("id");

alter table "book" add constraint "fk_book_published_by_id" foreign key ("published_by_id") references "publishing_house" ("id");

create index if not exists "idx_book_author_id_published_on_published_by_id" on "book" ("author_id", "published_on", "published_by_id");

create unique index if not exists "uq_book_title" on "book" ("title");
