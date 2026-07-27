create table users (
  id ${pk},
  email text not null unique,
  createdAt ${ts} not null default ${now_ts}
);
create index idx_users_email on users(email);
