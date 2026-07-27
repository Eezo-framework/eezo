create table tags (id ${pk}, name text not null unique);
create table post_tags (postId ${long} not null, tagId ${long} not null, primary key (postId, tagId));
