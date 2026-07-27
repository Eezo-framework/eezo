create table posts (
  id ${pk},
  userId ${long} not null references users(id) on delete cascade,
  title text not null,
  body text
);
create index idx_posts_user on posts(userId);
