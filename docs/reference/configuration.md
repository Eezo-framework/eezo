<!-- draft -->
# Configuration

Every environment variable eezo reads and every override an entry trait offers, with defaults.

> **TODO** This page is a placeholder. The outline below is what it should cover; write it by
> hand and remove the `draft` marker at the top when it is done.

## What to cover

- environment
  - `EEZO_SECRET`
  - `DATABASE_URL`; `EEZO_DB_URL`, `EEZO_DB_USER`, `EEZO_DB_PASS`
  - `EEZO_DB_POOL_SIZE`, `EEZO_DB_ACQUIRE_TIMEOUT`
- overrides on the entry traits
  - `HttpApp`: `port`, `maxBodySize`, `secret`, `problems`, `boot`, `routes`, `frameworkRoutes`
  - `DbApp`: `schema`, `databaseSchema`, `databaseInit`
  - `LiveApp`: `allowedOrigins`
  - `EezoApp`: all of the above
- defaults for each, and which are read once at boot

## Where the material is

- `modules/http/src/main/scala/io/eezo/http/HttpApp.scala`, `HttpConfig.scala`, `Secret.scala`
- `modules/db/src/main/scala/io/eezo/db/DbApp.scala`, `DbInit.scala`
- `modules/live/src/main/scala/io/eezo/live/LiveApp.scala`
