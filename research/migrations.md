# Migration engine and SQLite/Postgres dialect portability

Research ticket [#5](https://github.com/rcardin/eezo/issues/5). Investigated 2026-07-26.

## Question

eezo claims that one migration file targets SQLite in development and Postgres in production with no edits. Which engine makes that true: Flyway, Liquibase, or a runner eezo owns outright? Skiff solved it with placeholder tokens (`${skiff_pk}`, `${skiff_long}`, `${skiff_ts}`, `${skiff_now_ts}`, `${skiff_now_millis}`). Is that the state of the art?

## Summary of findings

1. **Liquibase is disqualified on licence.** Liquibase 5.0 and later is FSL-1.1-ALv2, not open source. eezo is GPLv3. The combination is not distributable.
2. **Flyway Community is genuinely Apache 2.0** and covers SQLite and Postgres, but the free tier gives you `migrate`, `info`, `validate`, `baseline`, `repair` and placeholders, and nothing else. `undo` is Teams. Schema diff and migration generation are Enterprise. Those are exactly the two features eezo's headline claims need.
3. **No tool abstracts the SQLite/Postgres gap well.** Liquibase, the only one that tries, emits SQL that SQLite rejects (verified against Liquibase's own test fixtures) and rebuilds SQLite tables using the precise sequence SQLite's documentation warns causes corruption.
4. **Placeholder substitution is not the state of the art, but it is the right technique for the hand written path.** The state of the art is a typed schema model rendered per dialect. Skiff already had that on the generated path. The two coexist correctly.
5. **The real portability risk is not DDL syntax, it is semantics.** Foreign keys are silently unenforced on SQLite by default (measured), and timestamp semantics differ in ways placeholders paper over rather than solve.
6. **Startup cost measured:** Flyway adds roughly 180 ms to a cold JVM no-op check, on top of a 190 ms JVM plus driver baseline. Inside a JVM it is 8 to 14 ms.

Recommendation and confidence are at the end.

---

## 1. The engines

### 1.1 Flyway: licence

This matters more than anything else in the evaluation, because eezo would hard depend on it and redistribute it.

The Flyway repository's `LICENSE.txt` reads in full:

> Copyright (C) Red Gate Software Ltd 2010-2026
>
> Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.

(Source: [`flyway/flyway` `LICENSE.txt`](https://github.com/flyway/flyway/blob/main/LICENSE.txt), retrieved via the GitHub API. GitHub's own licence detection for the repository reports `"spdx_id": "Apache-2.0"`.)

That is repository level. The important question is per artifact, because eezo would depend on specific Maven modules. Every source file carries an individual header. `flyway-core`:

> ```
> * ========================LICENSE_START=================================
> * flyway-core
> * ========================================================================
> * Copyright (C) 2010 - 2026 Red Gate Software Ltd
> * ========================================================================
> * Licensed under the Apache License, Version 2.0 (the "License");
> ```

(Source: [`ChecksumCalculator.java`](https://github.com/flyway/flyway/blob/main/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/ChecksumCalculator.java))

And `flyway-database-postgresql`, the module eezo would need for production:

> ```
> * flyway-database-postgresql
> * Copyright (C) 2010 - 2026 Red Gate Software Ltd
> * Licensed under the Apache License, Version 2.0 (the "License");
> ```

(Source: [`PostgreSQLAdvisoryLockTemplate.java`](https://github.com/flyway/flyway/blob/main/flyway-database/flyway-database-postgresql/src/main/java/org/flywaydb/database/postgresql/PostgreSQLAdvisoryLockTemplate.java))

**SQLite needs no extra module.** SQLite support lives inside `flyway-core` itself, not in a separate database plugin. A code search of the repository returns `flyway-core/src/main/java/org/flywaydb/core/internal/database/sqlite/SQLiteDatabase.java`, `SQLiteParser.java`, `SQLiteTable.java`, `SQLiteSchema.java`, `SQLiteConnection.java` and `SQLiteDatabaseType.java`. So both target databases are covered by Apache 2.0 artifacts.

**Apache 2.0 is compatible with eezo's GPLv3.** Apache 2.0 is one way compatible with GPLv3, so a GPLv3 work may incorporate Apache 2.0 dependencies. No problem here.

#### What the free tier does not include

The marketing page describes Flyway Community as "a free database migrations tool for individual developers and education" ([Redgate, Flyway editions](https://www.red-gate.com/products/flyway/editions/)), wording that reads restrictively. It is marketing copy, not the licence. The licence that governs the Maven artifacts is Apache 2.0, quoted above, and Apache 2.0 imposes no field of use restriction. A framework may depend on and redistribute `flyway-core` freely.

The real constraint is feature gating, not permission. Two gaps land directly on eezo's headline claims:

**Undo migrations are Teams.** The documentation states plainly that undo migrations are available in **Flyway Teams edition** ([Undo migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/undo-migrations)). Flyway's list of core commands marks it likewise: "Flyway's core commands (info, migrate, repair, validate, baseline, undo (Flyway Teams+))" ([Supported databases and versions](https://documentation.red-gate.com/flyway/getting-started-with-flyway/system-requirements/supported-databases-and-versions)). Since 14 May 2025 Flyway Teams is closed to new customers, who must buy Enterprise instead ([Redgate licensing](https://www.red-gate.com/products/flyway/community/)).

**Schema diff and migration generation are Enterprise.** The `diff` and `generate` commands, which produce a migration script from a schema comparison, are Enterprise features ([Generate](https://documentation.red-gate.com/flyway/reference/commands/generate); [Redgate, Scripting Databases with Flyway Enterprise CLI](https://www.red-gate.com/hub/product-learning/flyway/scripting-databases-with-flyway-enterprise-cli/)).

So Flyway Community gives eezo: apply pending migrations, track state, validate checksums, baseline, repair, and placeholder substitution. It does not give eezo down migrations or diffing. Both would have to be built anyway. Skiff discovered this in practice: its `Migrations.rollbackLast` runs the down script and deletes the `flyway_schema_history` row by hand, entirely outside Flyway (`/Users/rcardin/Documents/Repositories/skiff/modules/db/src/main/scala/skiff/db/Migrations.scala`).

### 1.2 Liquibase: disqualified on licence

**Liquibase 5.0 is no longer open source.** The `LICENSE.txt` at `master` is:

> Functional Source License, Version 1.1, ALv2 Future License
>
> Abbreviation: FSL-1.1-ALv2
>
> Notice: Copyright © 2025 Liquibase Inc.

(Source: [`liquibase/liquibase` `LICENSE.txt`](https://github.com/liquibase/liquibase/blob/master/LICENSE.txt), retrieved via the GitHub API. GitHub's licence detector reports `"spdx_id": "NOASSERTION"` for the repository, that is, it does not recognise the licence as a known open source one.)

The operative restriction, quoted verbatim from that file:

> **Permitted Purpose**
>
> A Permitted Purpose is any purpose other than a Competing Use. A Competing Use means making the Software available to others in a commercial product or service that:
> 1. substitutes for the Software;
> 2. substitutes for any other product or service we offer using the Software that exists as of the date we make the Software available; or
> 3. offers the same or substantially similar functionality as the Software.

And on redistribution:

> **Redistribution**
>
> The Terms and Conditions apply to all copies, modifications and derivatives of the Software.
>
> If you redistribute any copies, modifications or derivatives of the Software, you must include a copy of or a link to these Terms and Conditions and not remove any copyright notices provided in or with the Software.

There is a conversion clause:

> We hereby irrevocably grant you an additional license to use the Software under the Apache License, Version 2.0 that is effective on the second anniversary of the date we make the Software available.

Liquibase confirms the cutover and that it is not retroactive: "All versions of Liquibase Community released under Apache 2.0 remain under Apache 2.0. FSL applies only to Liquibase Community 5.0 and later" ([Liquibase, Strengthening Liquibase Community for the Future](https://www.liquibase.com/blog/liquibase-community-for-the-future-fsl)). Verified directly: `LICENSE.txt` at tag `v4.33.0` is still the Apache License 2.0, while `master` is FSL.

**Why this is fatal for eezo specifically.** eezo is GPLv3 (`/Users/rcardin/Documents/Repositories/eezo/LICENSE`). GPLv3 forbids imposing further restrictions on downstream recipients. FSL's Permitted Purpose clause is exactly such a restriction, a field of use limit. A GPLv3 framework cannot redistribute or hard depend on FSL licensed Liquibase 5.x. The Apache Software Foundation opened [LEGAL-721](https://issues.apache.org/jira/browse/LEGAL-721) on the same question for its own projects in December 2025; the issue notes Liquibase's own acknowledgement that "FSL does not technically meet the definition of 'open source' promulgated by the Open Source Initiative."

Pinning Liquibase 4.33 forever is technically possible but means depending on an abandoned branch of a schema tool. That is not a foundation for a framework.

**Even setting licence aside, Liquibase's SQLite support is broken.** Liquibase classifies PostgreSQL as Liquibase maintained and SQLite as community maintained ([What databases are supported by Liquibase?](https://docs.liquibase.com/secure/integration-guide-5-0/what-databases-are-supported-by-liquibase)). The consequences are visible in Liquibase's own saved state test fixtures, which record the SQL Liquibase is expected to emit per database. Compare, for identical changesets:

| Change type | Liquibase emits for SQLite | Liquibase emits for PostgreSQL |
|---|---|---|
| `modifyDataType` | `ALTER TABLE person ALTER COLUMN id TYPE INTEGER;` | `ALTER TABLE person ALTER COLUMN id TYPE INTEGER USING (id::INTEGER);` |
| `addDefaultValue` | *(nothing)* | `ALTER TABLE file ALTER COLUMN "fileName" SET DEFAULT 'Something Else';` |
| `dropForeignKeyConstraint` | *(nothing)* | `ALTER TABLE person DROP CONSTRAINT fk_address_person;` |
| `createIndex` | `CREATE INDEX ON person(id);` | `CREATE INDEX ON person(id);` |

(Source: [`liquibase-standard/src/test/resources/verify/saved_state/compareGeneratedSqlWithExpectedSqlForMinimalChangesets/`](https://github.com/liquibase/liquibase/tree/master/liquibase-standard/src/test/resources/verify/saved_state/compareGeneratedSqlWithExpectedSqlForMinimalChangesets), files `*/sqlite.sql` and `*/postgresql.sql`.)

Row by row:

- `ALTER TABLE ... ALTER COLUMN ... TYPE ...` does not exist in SQLite. SQLite's ALTER TABLE supports only rename table, rename column, add column, drop column, and (from 3.53.0) setting or dropping NOT NULL ([SQLite, ALTER TABLE](https://www.sqlite.org/lang_altertable.html)). This statement fails at runtime.
- Two change types silently produce no SQL at all. The migration reports success and the schema is not changed.
- `CREATE INDEX ON person(id)` omits the index name. That is legal Postgres, where "if the name is omitted, PostgreSQL chooses a suitable name based on the parent table's name and the indexed column name(s)" ([PostgreSQL, CREATE INDEX](https://www.postgresql.org/docs/current/sql-createindex.html)). It is illegal SQLite, whose grammar requires `CREATE [UNIQUE] INDEX [IF NOT EXISTS] schema-name.index-name ON table-name (...)` ([SQLite, CREATE INDEX](https://www.sqlite.org/lang_createindex.html)). The same fixture is shared and only one of the two databases accepts it.

Where Liquibase does implement a SQLite workaround, it is unsafe. `AddColumnGeneratorSQLite` (["Workaround for adding column on existing table for SQLite"](https://github.com/liquibase/liquibase/blob/master/liquibase-standard/src/main/java/liquibase/sqlgenerator/core/AddColumnGeneratorSQLite.java)) delegates to `SQLiteDatabase.AlterTableVisitor`, which performs this sequence:

```java
// rename table
String temp_table_name = tableName + "_temporary";
statements.add(new RenameTableStatement(catalogName, schemaName, tableName, temp_table_name));
// create temporary table  (under the ORIGINAL name)
...
// copy rows to temporary table
statements.add(new CopyRowsStatement(temp_table_name, tableName, copyColumns));
// delete original table
statements.add(new DropTableStatement(catalogName, schemaName, temp_table_name, false));
```

(Source: [`SQLiteDatabase.java`](https://github.com/liquibase/liquibase/blob/master/liquibase-standard/src/main/java/liquibase/database/core/SQLiteDatabase.java))

It renames the existing table out of the way first. SQLite's documentation warns against precisely this, in bold, at the end of the twelve step procedure:

> **Caution:** Take care to follow the procedure above precisely. [...] The safe procedure constructs the revised table definition using a new temporary name, then renames the table into its final name, which does not break links. **Do not rename the old table to a temporary name first, as this can corrupt references to that table in triggers, views, and foreign key constraints.**

(Source: [SQLite, ALTER TABLE, "Making Other Kinds Of Table Schema Changes"](https://www.sqlite.org/lang_altertable.html))

Note also that this rebuild fires for a plain `ADD COLUMN`, which SQLite supports natively. Liquibase pays a full table copy for an operation that is a metadata only change in SQLite.

Liquibase also does not disable `PRAGMA foreign_keys` around the rebuild, which is step 1 of SQLite's procedure, nor run `PRAGMA foreign_key_check` afterwards, which is step 10.

### 1.3 The case for eezo owning the runner

A migration runner's irreducible job is small:

1. List migration files in version order.
2. Read the applied set from a state table.
3. Apply what is missing, inside a transaction, recording each with a checksum.
4. Refuse to start if an applied file's checksum changed.
5. Hold a lock so concurrent starters do not race.

That is a few hundred lines. Skiff's own `Migrations.scala` is 136 lines and already reimplements the rollback half outside Flyway because Flyway Community does not provide it. The parts eezo actually needs and Flyway does not supply for free (undo, diffing) are larger than the part Flyway supplies.

The precedent is strong. Ecto (Elixir/Phoenix), Active Record (Rails) and Django all ship their own runner rather than wrapping a third party one, and all three get portability from a typed schema DSL rather than from SQL text. Discussed in §3.

The counterargument is real and should be stated: Flyway has fifteen years of edge case handling in `SQLiteParser`, statement splitting, encoding detection, BOM filtering, dollar quoted Postgres bodies, and lock retry. Rewriting that badly is a genuine risk. Skiff's own hand rolled rollback path shows the hazard: `Migrations.applyScript` splits statements by scanning lines, which breaks on any statement containing a semicolon inside a string literal or a `$$` quoted function body.

---

## 2. The dialect gap, construct by construct

The premise of the whole ticket is that there is no single raw SQL spelling that works on both. That premise is correct for a small number of constructs and, importantly, wrong for most of them. Establishing precisely which is which determines how large the placeholder vocabulary needs to be.

### 2.1 Autoincrement primary keys: genuinely irreconcilable

**SQLite.** A column declared `INTEGER PRIMARY KEY` becomes an alias for the table's 64 bit rowid and is auto assigned on insert ([SQLite, AUTOINCREMENT](https://www.sqlite.org/autoinc.html)). The `AUTOINCREMENT` keyword additionally guarantees that ids are never reused, at a cost SQLite explicitly discourages:

> The AUTOINCREMENT keyword imposes extra CPU, memory, disk space, and disk I/O overhead and should be avoided if not strictly needed. It is usually not needed.

Critically, the alias only applies to the exact type name `INTEGER`. `BIGINT PRIMARY KEY` is not a rowid alias and will not auto assign.

**Postgres.** `bigserial` is "not a true type, but merely a notational convenience for creating unique identifier columns", expanding to a sequence plus an integer column plus a default ([PostgreSQL, Numeric Types](https://www.postgresql.org/docs/current/datatype-numeric.html)). Postgres steers users to the SQL standard alternative instead:

> **Note:** This section describes a PostgreSQL-specific way to create an autoincrementing column. Another way is to use the SQL-standard identity column feature.

`GENERATED BY DEFAULT AS IDENTITY` is the modern spelling and does not exist in SQLite at all.

**Verdict:** irreconcilable. `${pk}` is justified. Skiff maps it to `integer primary key autoincrement` / `bigserial primary key`.

A note on the SQLite side of that mapping: given SQLite's own advice above, `integer primary key` without `AUTOINCREMENT` is the better default. The only behaviour `AUTOINCREMENT` adds is non reuse of ids after deletion, and it costs a write to `sqlite_sequence` on every insert. Since dev SQLite ids never need to match prod Postgres ids, the guarantee buys nothing.

### 2.2 64 bit integers: genuinely irreconcilable

SQLite's INTEGER storage class is already 64 bit: "the INTEGER storage class includes 7 different integer datatypes of different lengths" and values are stored in 0 to 8 bytes as needed ([SQLite, Datatypes](https://www.sqlite.org/datatype3.html)). Postgres `integer` is 4 bytes with range -2147483648 to +2147483647, and `bigint` is 8 bytes ([PostgreSQL, Numeric Types](https://www.postgresql.org/docs/current/datatype-numeric.html)).

Writing `bigint` in both would actually work syntactically, since SQLite gives any type name containing "INT" INTEGER affinity by rule 1 of the affinity rules ([SQLite, Datatypes](https://www.sqlite.org/datatype3.html)). But it interacts fatally with §2.1: `BIGINT PRIMARY KEY` is not a rowid alias, so a shared `bigint` spelling silently breaks autoincrement on SQLite.

**Verdict:** `${long}` is justified, mostly because of the interaction with the primary key rule. Skiff's rationale is recorded correctly in `PortableSql.scala`: an epoch millis value of about 1.75e12 overflows Postgres `int4`.

### 2.3 Timestamps and timezone semantics: the deepest gap

**Postgres** has a real type. `timestamp with time zone` (`timestamptz`) is 8 bytes and:

> All timezone-aware dates and times are stored internally in UTC.
>
> For `timestamp with time zone` values, an input string that includes an explicit time zone will be converted to UTC using the appropriate offset for that time zone. If no time zone is stated in the input string, then it is assumed to be in the time zone indicated by the system's TimeZone parameter [...] In either case, the value is stored internally as UTC, and the originally stated or assumed time zone is not retained.

([PostgreSQL, Date/Time Types](https://www.postgresql.org/docs/current/datatype-datetime.html))

Note the sting: for a bare input string with no offset, Postgres interprets it in the *server session's* `TimeZone`. The stored instant therefore depends on server configuration.

**SQLite** has no type at all:

> SQLite does not have a storage class set aside for storing dates and/or times. Instead, the built-in Date And Time Functions of SQLite are capable of storing dates and times as TEXT, REAL, or INTEGER values.

([SQLite, Datatypes](https://www.sqlite.org/datatype3.html))

**Verdict:** `${ts}` and `${now_ts}` are justified, but they are the weakest part of the placeholder approach, because the placeholder only fixes the *DDL*. It does not fix:

- **Ordering.** On Postgres, `ORDER BY created_at` on a `timestamptz` sorts by instant. On SQLite the column is TEXT and sorts lexicographically. Skiff's `${skiff_now_ts}` renders `strftime('%Y-%m-%dT%H:%M:%fZ','now')`, a fixed width, zero padded, always UTC ISO 8601 form, so lexicographic order does coincide with chronological order. That is correct but only by careful construction, and only for values the framework itself writes. Any application code that writes a differently formatted string, or a local time string, breaks ordering silently in dev and not in prod.
- **Comparison against parameters.** A JDBC `setTimestamp` binds differently on the two drivers.
- **Range and interval arithmetic.** `created_at > now() - interval '7 days'` is Postgres only.

This is the clearest case where a placeholder over raw SQL hides a semantic difference rather than resolving it. The read path has to agree with the write path, and placeholders only cover the write path's DDL.

Skiff also carries `${skiff_now_millis}` for epoch millis columns, with SQLite `(cast(strftime('%s','now') as integer) * 1000)` and Postgres `(extract(epoch from now())::bigint * 1000)`. Storing epoch millis in a `${long}` column sidesteps the entire problem: an INTEGER on both sides, ordering identical, arithmetic identical, no timezone semantics anywhere. That is the more robust of skiff's two timestamp strategies, and skiff's own `DEFERRED.md` records an unresolved migration between them.

Note the SQLite `strftime('%s','now')` form has whole second resolution, so the two strategies are not interchangeable if sub second ordering matters.

### 2.4 Booleans: no placeholder needed

**SQLite:**

> SQLite does not have a separate Boolean storage class. Instead, Boolean values are stored as integers 0 (false) and 1 (true). SQLite recognizes the keywords "TRUE" and "FALSE", as of version 3.23.0 [...] however those keywords are really just alternative spellings for the integer literals 1 and 0 respectively.

([SQLite, Datatypes](https://www.sqlite.org/datatype3.html))

**Postgres** has a real 1 byte `boolean` accepting `TRUE`/`FALSE` keywords ([PostgreSQL, Boolean Type](https://www.postgresql.org/docs/current/datatype-boolean.html)).

The word `BOOLEAN` is accepted by both. On SQLite it falls to rule 5 of the affinity rules and gets NUMERIC affinity, and `DEFAULT TRUE` works on both since 3.23.0. Flyway itself relies on this: the `flyway_schema_history` table it created on SQLite in my test run contains `"success" BOOLEAN NOT NULL`.

**Verdict:** `boolean` needs no placeholder. But note the divergence in what comes back: Postgres returns a real boolean, SQLite returns an integer. That is a JDBC layer concern, not a migration concern, and `getBoolean` handles both.

### 2.5 UUID storage: no placeholder, but a decision to make

**Postgres** has a native 128 bit `uuid` type, 16 bytes, and generates v4 and v7 natively ([PostgreSQL, UUID Type](https://www.postgresql.org/docs/current/datatype-uuid.html)).

**SQLite** has no UUID type. The options are TEXT (36 characters) or BLOB (16 bytes).

There is no shared spelling. `UUID` written on SQLite falls to affinity rule 5 (NUMERIC), which is wrong for a hex string with hyphens; values would be stored as TEXT anyway since they will not losslessly convert, but the declared affinity is misleading and in a STRICT table it is illegal outright.

**Verdict:** either a `${uuid}` placeholder (`uuid` / `text`), or the simpler policy of always storing UUIDs as `text` on both sides and accepting 36 bytes plus no native operators on Postgres. Skiff has no UUID placeholder, implying it uses `${long}` ids throughout. If eezo wants UUID primary keys it needs to decide this explicitly.

### 2.6 Text sizes: no placeholder needed, and length is a lie on SQLite

Postgres:

> There is no performance difference among these three types, apart from increased storage space when using the blank-padded type, and a few extra CPU cycles to check the length when storing into a length-constrained column. While `character(n)` has performance advantages in some other database systems, there is no such advantage in PostgreSQL; in fact `character(n)` is usually the slowest of the three because of its additional storage costs. In most situations `text` or `character varying` should be used instead.

([PostgreSQL, Character Types](https://www.postgresql.org/docs/current/datatype-character.html))

SQLite gives `VARCHAR(255)` TEXT affinity by rule 2, and does not enforce the length ([SQLite, Datatypes](https://www.sqlite.org/datatype3.html)). So `varchar(255)` is *accepted* by both but *enforced* by only one. A value of 300 characters inserts cleanly in dev and raises an error in production. That is the dev/prod divergence eezo exists to eliminate.

**Verdict:** use `text` everywhere, no placeholder. Enforce length in the model layer where it is enforced identically in both environments. This is one where the framework should be opinionated rather than portable.

### 2.7 Indexes: nearly portable

`CREATE INDEX idx_name ON table(col)` works on both. `IF NOT EXISTS` works on both. Partial indexes via `WHERE` work on both ([SQLite, CREATE INDEX](https://www.sqlite.org/lang_createindex.html); [PostgreSQL, CREATE INDEX](https://www.postgresql.org/docs/current/sql-createindex.html)).

Two asymmetries:

- The index name is mandatory on SQLite, optional on Postgres. Always name indexes. This is the exact hole Liquibase falls through (§1.2).
- Postgres has `CREATE INDEX CONCURRENTLY`, which is how you add an index to a live production table without locking writes. It has a hard constraint: "A regular `CREATE INDEX` command can be performed within a transaction block, but `CREATE INDEX CONCURRENTLY` cannot." SQLite has no equivalent and no need for one.

That second point is a design constraint on the runner, not on the DDL vocabulary: if eezo wraps each migration in a transaction (it should), it needs an escape hatch for a migration that must run outside one. Flyway exposes this as a per script configuration; a hand rolled runner needs the same, for instance a `-- eezo:no-transaction` header.

**Verdict:** no placeholder needed. A naming rule and a no-transaction escape hatch are needed.

### 2.8 Foreign keys: the largest silent divergence, measured

`references users(id) on delete cascade` parses identically on both. It is *enforced* on only one.

> Foreign key constraints are disabled by default (for backwards compatibility), so must be enabled separately for each database connection. (Note, however, that future releases of SQLite might change so that foreign key constraints enabled by default. Careful developers will not make any assumptions about whether or not foreign keys are enabled by default but will instead enable or disable them as necessary.)

([SQLite, Foreign Key Support](https://www.sqlite.org/foreignkeys.html))

I confirmed this empirically against `org.xerial:sqlite-jdbc:3.50.3.0`, the standard JDBC driver:

```
--- fk default ---
pragma foreign_keys = 0
```

So in a default eezo dev setup, every `references` clause in every migration is decorative. `ON DELETE CASCADE` does not cascade. Orphaned rows are accepted. The application passes in development and fails in production, which is the precise failure mode the one-file claim is meant to prevent.

This is not fixed by any placeholder and no migration engine fixes it either. It is fixed by the connection pool issuing `PRAGMA foreign_keys = ON` on every connection checkout, per SQLite's instruction that it is per connection. **This belongs in eezo's connection setup and should be treated as non negotiable.** It is arguably the single most valuable finding in this document for the one-file claim.

### 2.9 ALTER TABLE: the constraint that shapes everything

SQLite supports only:

> The ALTER TABLE command in SQLite allows these alterations of an existing table: it can be renamed; a column can be renamed; a column can be added to it; or a column can be dropped from it.

plus setting or dropping NOT NULL as of 3.53.0 ([SQLite, ALTER TABLE](https://www.sqlite.org/lang_altertable.html)).

`ADD COLUMN` is further restricted. Quoting the same page, the new column:

- may not have a PRIMARY KEY or UNIQUE constraint;
- may not have a default of `CURRENT_TIME`, `CURRENT_DATE`, `CURRENT_TIMESTAMP`, or a parenthesised expression;
- must have a non NULL default if declared NOT NULL;
- must default to NULL if it adds a REFERENCES clause while foreign keys are enabled;
- may not be `GENERATED ALWAYS ... STORED`.

The third restriction has a nasty corollary for portability. **A migration adding a NOT NULL timestamp column cannot use the same default expression as the migration that created the table.** `${now_ts}` renders to `(strftime(...))` on SQLite, a parenthesised expression, which `ADD COLUMN` forbids outright. Skiff's placeholder vocabulary works for `CREATE TABLE` and breaks for `ALTER TABLE ADD COLUMN`. I did not find this documented in skiff.

Postgres, by contrast, supports changing types, adding and dropping arbitrary constraints, and altering defaults freely, and does it all transactionally.

Everything else on SQLite requires the twelve step rebuild, quoted here in full because any hand rolled runner that supports type changes has to implement it:

> 1. If foreign key constraints are enabled, disable them using `PRAGMA foreign_keys=OFF`.
> 2. Start a transaction.
> 3. Remember the format of all indexes, triggers, and views associated with table X. [...] `SELECT type, sql FROM sqlite_schema WHERE tbl_name='X'`.
> 4. Use CREATE TABLE to construct a new table "new_X" that is in the desired revised format of table X. [...]
> 5. Transfer content from X into new_X using a statement like: `INSERT INTO new_X SELECT ... FROM X`.
> 6. Drop the old table X: `DROP TABLE X`.
> 7. Change the name of new_X to X using: `ALTER TABLE new_X RENAME TO X`.
> 8. Use CREATE INDEX, CREATE TRIGGER, and CREATE VIEW to reconstruct indexes, triggers, and views associated with table X. [...]
> 9. If any views refer to table X in a way that is affected by the schema change, then drop those views [...] and recreate them [...].
> 10. If foreign key constraints were originally enabled then run `PRAGMA foreign_key_check` to verify that the schema change did not break any foreign key constraints.
> 11. Commit the transaction started in step 2.
> 12. If foreign keys constraints were originally enabled, reenable them now.

Note steps 4 and 7: the *new* table gets the temporary name and is renamed into place. This is the opposite of what Liquibase does (§1.2).

**Verdict:** the honest design response is to make the twelve step rebuild unnecessary rather than to implement it. Additive migrations (add column nullable, add index, add table) work natively on both. Type changes and constraint additions are the hard cases. A framework whose diffing generates only additive changes by default, and refuses destructive ones without an explicit flag, avoids most of this. That is roughly what skiff's `AutoMigrations` does with its safe versus destructive classification.

### 2.10 Two things that are the same, and one worth correcting

**Transactional DDL works on both.** Flyway's FAQ states:

> Unfortunately, today only DB2, PostgreSQL, Derby, EnterpriseDB and to a certain extent SQL Server support DDL statements inside a transaction.

([Flyway FAQ](https://documentation.red-gate.com/fd/frequently-asked-questions-277579363.html))

SQLite is not on that list, but the statement is misleading for SQLite. I tested it directly:

```
--- transactional DDL test ---
DDL rolled back OK: [SQLITE_ERROR] SQL error or missing database (no such table: rollme)
```

A `CREATE TABLE` inside an open transaction, then rolled back, leaves no table. SQLite DDL is transactional. This is good news: a failed migration leaves no partial schema on either target, so a hand rolled runner can rely on transaction rollback uniformly. This is a materially stronger position than a runner facing MySQL or Oracle.

**STRICT tables are worth knowing about.** Since SQLite 3.37.0 (2021-11-27) a table may be declared `STRICT`, in which case "Every column definition must specify a datatype" and only `INT`, `INTEGER`, `REAL`, `TEXT`, `BLOB`, `ANY` are permitted; a value that "cannot be losslessly converted in the specified datatype" raises `SQLITE_CONSTRAINT_DATATYPE` ([SQLite, STRICT Tables](https://www.sqlite.org/stricttables.html)).

This is directly relevant to the one-file claim, because it removes the class of bug where SQLite quietly accepts what Postgres rejects. It also constrains the vocabulary usefully: under STRICT, `varchar(255)`, `boolean` and `uuid` are all illegal on SQLite, which forces the vocabulary toward the portable subset anyway. The cost is that `${pk}` must render `integer primary key` (still a valid rowid alias in a STRICT table) and `${ts}` must render `text`, both of which skiff already does modulo the `autoincrement` keyword.

Recommend eezo emit `STRICT` on SQLite. It converts a silent dev/prod divergence into a loud dev time error, which is exactly the trade the framework wants.

---

## 3. Is placeholder substitution the state of the art?

No, but the honest answer is more nuanced than the question implies, because two different problems are being conflated.

### 3.1 The four known techniques

**(a) Placeholder tokens over raw SQL.** Skiff's approach. Note that this is not a skiff invention: it is Flyway's own built in feature. Flyway substitutes "Ant-style" `${placeholder}` tokens in SQL migrations at apply time, configured via `Flyway.configure().placeholders(map)` ([Flyway, Migration Placeholders](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-placeholders)). Liquibase has the same idea with a per database twist, `<property name="clob.type" value="clob" dbms="oracle,postgresql"/>` ([Liquibase, Property substitution](https://docs.liquibase.com/concepts/changelogs/property-substitution.html)).

*Strength:* the migration is still readable SQL. The escape hatch to raw dialect specific SQL is always available, because it is all raw SQL. The vocabulary stays small: skiff needs only five tokens, and my construct by construct analysis in §2 confirms only about five constructs genuinely need one.

*Weakness:* nothing validates that a migration is portable until it runs on the other database. A developer writes `alter table x alter column y type text`, it works on Postgres, and it fails the first time someone runs it on SQLite. There is no type system, no lint, no check. The one-file claim is enforced by discipline only.

**One important thing skiff got right, verified from Flyway's source.** A placeholder based migration produces different SQL text on SQLite and on Postgres. If Flyway checksummed the *resolved* SQL, then the same file would have different checksums in dev and prod, and validation would break the moment a database was shared or a history table compared. It does not. In `SqlMigrationResolver`, placeholder replacement is folded into the checksum only for repeatable migrations:

```java
private Integer getChecksumForLoadableResource(final boolean repeatable, ..., final boolean placeholderReplacement) {
    if (repeatable && placeholderReplacement) {
        parsingContext.updateFilenamePlaceholder(resourceName, configuration);
        return ChecksumCalculator.calculate(createPlaceholderReplacingLoadableResources(loadableResources));
    }
    return ChecksumCalculator.calculate(loadableResources.toArray(LoadableResource[]::new));
}
```

([`SqlMigrationResolver.java`](https://github.com/flyway/flyway/blob/main/flyway-core/src/main/java/org/flywaydb/core/internal/resolver/sql/SqlMigrationResolver.java))

Versioned migrations checksum the raw file, so the checksum is dialect independent. This matches the documented note that "Changing placeholder values will trigger repeatable migrations to re-run". **Any eezo owned runner must replicate this: checksum the raw file, never the resolved SQL.** It is a one line decision that is very easy to get wrong and very painful to discover later.

**(b) A typed schema DSL in the host language, rendered per dialect.** Rails, Ecto and Django. The migration is code describing intent, and the adapter renders dialect specific DDL.

Ecto: "The Ecto primitive types are mapped to the appropriate database type by the various database adapters. For example, `:string` is converted to `:varchar`, `:binary` to `:bytea` or `:blob`, and so on" ([Ecto.Migration](https://ecto-sql.hexdocs.pm/Ecto.Migration.html)).

Liquibase's changelog is the Java ecosystem's version of this, and its `liquibase.datatype.core` package is the reference implementation: `BigIntType`, `BooleanType`, `UUIDType`, `TimestampType` and so on, each with a `toDatabaseDataType(Database)` method branching per dialect. `BooleanType` alone branches across Firebird (version dependent), Db2z, MSSQL, MySQL, MariaDB, Oracle (version dependent), Sybase and Derby ([`BooleanType.java`](https://github.com/liquibase/liquibase/blob/master/liquibase-standard/src/main/java/liquibase/datatype/core/BooleanType.java)).

*Strength:* portability is structural. You cannot express a non portable migration without deliberately reaching for the escape hatch. Reversibility falls out for free (§4). Diffing falls out for free (§5).

*Weakness:* it is a much larger surface, and the escape hatch is worse. When you need `CREATE INDEX CONCURRENTLY` or a partial index with a tricky predicate, you drop to raw SQL and lose reversibility along with it. Ecto is explicit that bare `execute/1` is not reversible.

**(c) Declarative schema as code with a diffing planner.** Atlas ([atlasgo.io](https://atlasgo.io)) is the current best in class here. You declare the desired schema; Atlas diffs it against the database and plans the migration. Its core is Apache 2.0 (`ariga/atlas` repository licence confirmed Apache-2.0 via the GitHub API). But the safety features are paywalled: `migrate lint`, which detects destructive and unsafe changes, and `migrate checkpoint` are not in the Community Edition and require `atlas login` ([Atlas, Community Edition](https://atlasgo.io/community-edition); [Atlas, login-required](https://atlasgo.io/components/login-required)). It is also a Go binary, so a Scala framework would be shelling out to an external executable, which is a poor fit for a framework promising one command deploys.

**(d) Ship two files.** Rejected by the ticket's premise, but worth naming as the thing everyone else does.

### 3.2 The synthesis

The important observation is that **skiff already used both (a) and (b), for different paths**, and the ticket frames only (a) as skiff's answer.

- Hand written migrations under `src/main/resources/migrations/` use placeholders, resolved by Flyway (`Migrations.run` calls `.placeholders(PortableSql.flywayPlaceholders(dialect))`).
- Generated migrations come from `AutoMigrations`, which diffs a typed `Schema` derived from case classes against a JSON snapshot and calls `SqlRenderer.render(changes, dialect)`. That path never needs a placeholder, because it renders dialect specific SQL directly from a typed model. Skiff's own comment confirms it: "Cross-dialect: `SqlRenderer` picks the right syntax based on the `Dialect` inferred from `app.conf`'s `db.url`. The snapshot itself is dialect-agnostic."

That division is correct and should be kept. The typed model is the source of truth and handles the common case with structural guarantees. Placeholders are the escape hatch for the hand written case, where the developer has already chosen to write raw SQL and needs a way to spell the five irreconcilable constructs.

So: **placeholders are not the state of the art as a primary mechanism, but they are the right state of the art for the escape hatch.** The improvement over skiff is not to replace them, it is to:

1. Keep the typed renderer as the primary path (already true in skiff).
2. Shrink the placeholder vocabulary to what §2 shows is genuinely irreconcilable, and drop `varchar(n)` and `boolean` guidance in favour of `text` and `boolean` as portable raw SQL.
3. Add the thing skiff lacks: **a portability check that does not require running both databases.** Because eezo owns the runner, it can, on every dev startup, resolve each migration for *both* dialects and parse them, or more cheaply, lint the raw SQL for known non portable constructs (`alter column`, `varchar(`, `serial`, `timestamptz`, `now()`, `autoincrement`, `uuid`, unnamed `create index`) and fail loudly with the placeholder to use instead. That converts the one-file claim from a discipline into a check. No existing tool does this, and it costs almost nothing at startup.

---

## 4. Reversible down migrations, and whether anything verifies faithfulness

### 4.1 What the tools offer

**Flyway:** undo is a paid feature (§1.1), and Flyway is notably pessimistic about the whole idea even for customers:

> If your versioned migration script contains destructive changes (drop, delete, truncate, ...), then restoring both table and data in the undo script can be challenging.

and

> [Flyway recommends] maintaining backwards compatibility between the DB and all versions of the code currently deployed in production, combined with a proper, well tested, backup and restore strategy.

([Flyway, Undo migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/undo-migrations))

Flyway also notes undo cannot help a *partially* failed migration on a database without transactional DDL. That caveat does not apply to eezo, since both its targets have transactional DDL (§2.10).

**Liquibase:** auto rollback is real for structural change types. The `createTable` change type reports "Auto Rollback: Yes" across databases including SQLite ([Liquibase, createTable](https://docs.liquibase.com/change-types/create-table.html)). This works because the changelog is a typed model, so the inverse is derivable.

**Rails:** the `change` method covers "the majority of cases in which Active Record knows how to reverse a migration's actions automatically", with a documented list of reversible operations (`add_column`, `remove_column`, `add_index`, `create_table`, `rename_column`, `add_foreign_key`, `change_column_null` and `change_column_default` when given `:from` and `:to`, and so on). Anything else raises `ActiveRecord::IrreversibleMigration` ([Rails, Active Record Migrations](https://guides.rubyonrails.org/active_record_migrations.html)).

**Ecto:** the same design. "Trying to rollback a non-reversible command will raise an `Ecto.MigrationError`", and `execute/2` exists specifically so you can supply the inverse of raw SQL yourself ([Ecto.Migration](https://ecto-sql.hexdocs.pm/Ecto.Migration.html)).

The pattern is unambiguous: **automatic reversibility is a property of the typed model approach, not of the SQL text approach.** You get it for free from (b) and you cannot get it at all from (a).

### 4.2 Does anything verify a down is faithful?

**No. Nothing in any of these tools verifies it.** I could not find a single mainstream migration tool that checks a down migration actually restores the prior schema.

What exists is adjacent but different:

- Rails and Ecto guarantee *derivability*, not faithfulness. If the framework derived the inverse it is correct by construction, but for a hand written `down` or a raw `execute` nothing checks anything.
- Atlas `migrate lint` analyses *forward* migrations for destructive operations, table locks and backwards incompatible changes ([Atlas, migrate lint](https://atlasgo.io/versioned/lint)). It does not analyse downs, and it is paywalled anyway.
- Skiff went further than any of them. `AutoMigrations` computes a `downFaithful` flag, and when the up drops data it stamps the down file with `-- skiff:unfaithful`, which `Migrations.rollbackLast` refuses to execute without `--force` (`/Users/rcardin/Documents/Repositories/skiff/modules/db/src/main/scala/skiff/db/AutoMigrations.scala`, `Migrations.scala`). This is a *classification* of faithfulness, decided at generation time from the change list, not a verification.

**The verification technique that nobody ships is straightforward and eezo could.** Because eezo has SQLite available in dev at zero cost, a round trip test is cheap:

1. Apply migrations up to version N-1 against a throwaway in memory SQLite database.
2. Snapshot the schema (`SELECT type, name, sql FROM sqlite_schema`, exactly what step 3 of SQLite's own rebuild procedure uses).
3. Apply N, then apply N's down.
4. Snapshot again and compare.

If they differ, the down is unfaithful in structure. This does not verify data faithfulness, which is undecidable in general and is precisely what skiff's `unfaithful` marker is for, but structural faithfulness is checkable and currently nobody checks it. This would be a genuine differentiator, it is maybe fifty lines, and it runs in milliseconds against `jdbc:sqlite::memory:`. It should run in CI rather than on every dev reload.

---

## 5. Schema diffing from a changed case class

This is a headline eezo feature ("the case class is the source of truth", per the README) and the tooling landscape is thin.

- **Flyway:** Enterprise only (§1.1). Not available.
- **Liquibase:** `diff-changelog` compares two *databases*, not a model and a database, and is licence blocked for eezo anyway. Its documented limitations are instructive regardless: "Liquibase does not currently check data type length"; generated changelogs "should be inspected for correctness and completeness before being deployed"; and with object sorting disabled it "may create objects in the wrong order (such as a view on a table before the table itself)" ([Liquibase, diff-changelog](https://docs.liquibase.com/commands/inspection/diff-changelog.html)).
- **Atlas:** does exactly this and does it well, but is a Go binary and its safety analysis is paywalled (§3.1c).
- **Django** is the closest analogue to what eezo wants: `makemigrations` autodetects changes between the model classes and the recorded migration state and writes a migration. It is framework owned, not a third party dependency, which is the point.

**There is no reusable component here.** Every framework that offers model to migration diffing built it. Skiff built it (`AutoMigrations`, 181 lines, plus `SqlRenderer` and a `Schema`/`TableSpec` model). eezo will build it. This is decisive for the engine question, because the diffing component *already needs* a typed schema model and a per dialect SQL renderer. Once those exist, the incremental cost of also owning the applier is small, and the value of a third party applier drops correspondingly.

One design note from skiff worth carrying forward: it diffs the model against a **JSON snapshot file** committed to the repository (`migrations/.skiff_schema.json`), not against the live database. That is the right call. Diffing against the live database requires the database to be reachable and correct, and gives different answers in dev and prod. A committed snapshot is deterministic, reviewable in a pull request, and dialect agnostic (skiff's comment: "The snapshot itself is dialect-agnostic"). Django uses the same approach via its migration files.

---

## 6. Migration state tracking and concurrent startup

### 6.1 State tables

**Flyway** records applied migrations in `flyway_schema_history`. Here is the table it actually created on SQLite in my test run, dumped from `sqlite_schema`:

```sql
CREATE TABLE "flyway_schema_history" (
    "installed_rank" INT NOT NULL PRIMARY KEY,
    "version" VARCHAR(50),
    "description" VARCHAR(200) NOT NULL,
    "type" VARCHAR(20) NOT NULL,
    "script" VARCHAR(1000) NOT NULL,
    "checksum" INT,
    "installed_by" VARCHAR(100) NOT NULL,
    "installed_on" TEXT NOT NULL DEFAULT (strftime('%Y-%m-%d %H:%M:%f','now')),
    "execution_time" INT NOT NULL,
    "success" BOOLEAN NOT NULL
);
CREATE INDEX "flyway_schema_history_s_idx" ON "flyway_schema_history" ("success");
```

Note it uses `TEXT` with an ISO-ish `strftime` default for its own timestamp column and `BOOLEAN` for a flag, that is, exactly the portable subset §2 arrives at independently. Worth taking as corroboration.

The `checksum` column is what makes `validate` work: an already applied migration whose file changed is detected and refused.

**Liquibase** uses `DATABASECHANGELOG` plus a separate `DATABASECHANGELOGLOCK` ([Liquibase, DATABASECHANGELOGLOCK](https://docs.liquibase.com/concepts/tracking-tables/databasechangeloglock-table.html)).

### 6.2 Concurrent startup

This is where the two designs diverge sharply, and it is a strong argument in Flyway's favour on engineering quality.

**Flyway** uses the database's own locking. Its FAQ: "Flyway uses the locking technology of your database to coordinate multiple nodes. This ensures that even if multiple instances of your application attempt to migrate the database at the same time, it still works" ([Flyway FAQ](https://documentation.red-gate.com/fd/frequently-asked-questions-277579363.html)). For Postgres this is concretely a session or transaction scoped advisory lock. From `PostgreSQLAdvisoryLockTemplate.java`:

```java
jdbcTemplate.query("SELECT pg_try_advisory_xact_lock(" + lockNum + ")", ...)
jdbcTemplate.query("SELECT pg_try_advisory_lock(" + lockNum + ")", ...)
jdbcTemplate.queryForBoolean("SELECT pg_advisory_unlock(" + lockNum + ")")
```

If the lock is unavailable Flyway retries at one second intervals up to a configurable count ([Flyway Lock Retry Count Setting](https://documentation.red-gate.com/fd/flyway-lock-retry-count-setting-277579009.html)).

The crucial property: a Postgres advisory lock is **released automatically when the session ends**. A pod that is SIGKILLed mid migration releases its lock when the connection drops. There is no stale lock.

**Liquibase** takes the opposite approach: a row in `DATABASECHANGELOGLOCK` with `LOCKED=1`. If the process dies the row stays locked and a human must run `liquibase release-locks`, which is documented as executing `UPDATE DATABASECHANGELOGLOCK SET LOCKED=0`. In a Kubernetes rollout, one OOM killed pod wedges every subsequent deploy until someone intervenes manually. This is a well known operational failure mode and it is a direct consequence of implementing the lock in application state rather than database state.

**For an eezo owned runner**, the design follows:

- **Postgres:** `pg_advisory_lock(<constant>)` on a dedicated connection held for the duration, released on connection close. This is about five lines and inherits the crash safety property for free. Do not invent a lock table.
- **SQLite:** the question is close to moot. SQLite serialises writers at the file level, and a dev SQLite database has one application instance. Wrapping the whole migration run in `BEGIN IMMEDIATE` is sufficient and gets the file lock. Multi instance startup against a shared SQLite file is not a scenario eezo needs to support.

Combined with §2.10 (transactional DDL on both), an eezo runner has a genuinely simple concurrency story: take the lock, begin a transaction, apply, record, commit, release. Both targets support every step. This is the single strongest technical argument that owning the runner is tractable: Flyway's complexity here exists mostly to serve MySQL and Oracle, which eezo does not target.

---

## 7. Startup cost

Migration checks sit inside a sub three second dev reload loop, so I measured rather than guessed.

**Method.** Flyway 11.14.0, `org.xerial:sqlite-jdbc:3.50.3.0`, `slf4j-nop`, JDK on Darwin arm64. Three migration files using placeholders, applied to a local SQLite file. Two scenarios: repeated calls within one JVM, and a fresh JVM per call. The comparison baseline is a minimal hand rolled check (open connection, `SELECT version FROM eezo_migrations`, list the directory, compare), which is what an eezo owned runner does on the hot path. Full harness in the appendix.

**Within a single JVM:**

| Operation | Time |
|---|---|
| Flyway, first call, 3 migrations applied | 427 ms |
| Flyway, subsequent no-op checks | 8 to 14 ms |
| Own runner, no-op check | < 1 ms |

**Fresh JVM per invocation (5 runs each), nothing pending:**

| Operation | Times (ms) | Median |
|---|---|---|
| Flyway cold JVM no-op | 373, 383, 360, 381, 377 | 377 |
| Own runner cold JVM no-op | 207, 193, 190, 192, 194 | 193 |

**Interpretation.** The 193 ms baseline is JVM start plus SQLite JDBC native library load, which eezo pays regardless. The **marginal cost of Flyway is about 180 ms per cold start**, spent on class loading and its `ServiceLoader` plugin scan. `flyway-core` is 737 KB and pulls in `jackson-databind` (1.68 MB), `jackson-core` (591 KB) and `jackson-annotations` (78 KB), roughly 3 MB of classpath for a no-op.

Against a three second budget, 180 ms is about 6%. Not disqualifying, but not free, and it is pure overhead on the overwhelmingly common case where nothing has changed.

**The bigger point is that this only matters if the reload restarts the JVM.** If eezo's dev reload swaps a classloader inside a live JVM, Flyway's no-op is 8 to 14 ms and the whole question is moot. If it forks a JVM, 180 ms is a real slice. This should be checked against eezo's actual reload design before it is weighted heavily.

**A cheaper strategy than either, available only to a runner eezo owns:** skip the database entirely when nothing changed. Hash the migration directory (names, sizes, mtimes) and cache it alongside the last successful run. If the hash matches, do not open a connection at all. That takes the hot path to effectively zero and is not expressible through Flyway's API.

---

## 8. Recommendation

**Own the migration runner. Do not depend on Flyway or Liquibase.**

The reasoning, in order of weight:

1. **Liquibase is not available.** FSL-1.1-ALv2 from 5.0 is not open source and its Permitted Purpose restriction is incompatible with eezo's GPLv3. Pinning 4.33 forever is not a foundation. This is not a judgement call.

2. **Flyway Community does not supply the features eezo's claims need.** Undo is Teams. Diff and generate are Enterprise. eezo must build reversible migrations and model to schema diffing itself either way. Skiff proved this: it already reimplemented rollback outside Flyway, and its `AutoMigrations` renderer already bypasses placeholders entirely for the generated path.

3. **The typed renderer is being built regardless, and it subsumes the applier.** Diffing needs a typed schema model and per dialect SQL rendering. Once those exist, applying ordered files against a state table is a few hundred lines. The value Flyway adds shrinks to statement parsing and locking.

4. **Both targets have transactional DDL and both have a clean locking story.** Flyway's real complexity serves MySQL and Oracle. eezo targets neither. The concurrency and failure semantics eezo needs (advisory lock on Postgres, `BEGIN IMMEDIATE` on SQLite, transaction rollback on failure everywhere) are genuinely simple, and I verified transactional DDL on SQLite directly rather than trusting Flyway's FAQ, which omits it.

5. **Startup cost favours owning it, modestly.** About 180 ms per cold JVM, and a directory hash short circuit that Flyway's API cannot express takes the hot path to zero.

**On the placeholder question specifically:** keep placeholders, but demote them. They are the escape hatch for hand written SQL, not the primary portability mechanism. The primary mechanism is the typed schema model rendered per dialect, which skiff already had. Concretely:

- Keep `${pk}`, `${long}`, `${ts}`, `${now_ts}`, `${now_millis}`. §2 confirms these five constructs are genuinely irreconcilable.
- Consider adding `${uuid}` only if eezo supports UUID keys.
- Do *not* add placeholders for booleans, text, indexes or foreign key clauses. Those are already portable raw SQL and a larger vocabulary is a larger thing to learn.
- Render `${pk}` as `integer primary key` on SQLite, without `AUTOINCREMENT`, per SQLite's own advice that it "should be avoided if not strictly needed. It is usually not needed."

**Five things to get right that skiff did not, or that no tool does:**

1. **`PRAGMA foreign_keys = ON` on every SQLite connection checkout.** Measured off by default. Without this, every `references` clause in every migration is decorative in dev and enforced in prod, which is the exact divergence the one-file claim promises to eliminate. Highest value item in this document.
2. **Checksum the raw migration file, never the resolved SQL.** Verified from Flyway's `SqlMigrationResolver`. Getting this wrong makes dev and prod checksums disagree, and the failure surfaces late and confusingly.
3. **Emit `STRICT` on SQLite tables.** Turns silent affinity coercions into loud dev time errors and forces the vocabulary toward the portable subset.
4. **Lint migrations for non portable constructs at dev startup.** No existing tool does this. It converts the one-file claim from a discipline into a check, and it costs microseconds.
5. **Handle the `ADD COLUMN` restriction.** SQLite forbids a parenthesised default on `ADD COLUMN`, so `${now_ts}` cannot be used there even though it works in `CREATE TABLE`. Skiff's vocabulary has this hole. Either the linter catches it or the renderer emits the backfill-then-set-not-null pattern.

**Do not implement the twelve step SQLite rebuild.** Make it unnecessary by keeping generated migrations additive and requiring an explicit flag for destructive changes, which is roughly skiff's existing safe/destructive classification. If a rebuild is ever needed, follow SQLite's ordering exactly (new table under a temporary name, renamed into place) and not Liquibase's, which SQLite's documentation explicitly warns corrupts references.

**Reuse Flyway's `flyway_schema_history` shape** (version, description, script, checksum, installed_by, installed_on, execution_time, success) even under a different table name. It is a proven design and it keeps migration off ramps open.

### Confidence

**High** on the licence findings. Both licence texts were read in full from the projects' own repositories, and the edition gating for undo, diff and generate is stated explicitly in Redgate's documentation. The GPLv3 versus FSL incompatibility is a straightforward reading of GPLv3's no-further-restrictions rule, though it is a legal conclusion rather than a technical one and a lawyer should confirm it before it is written into project documentation.

**High** on the dialect gap analysis. Every construct is cited to SQLite's or PostgreSQL's own documentation, and the foreign key default, transactional DDL behaviour and rendered schema were verified empirically rather than assumed.

**High** on the Liquibase SQLite defects. They come from Liquibase's own saved state test fixtures and its own source, not from third party reports.

**Medium-high** on the recommendation to own the runner. The reasoning is sound and the licence constraint removes the main alternative, but it trades a mature dependency for code eezo maintains. The specific risk is SQL statement splitting: skiff's `Migrations.applyScript` splits on lines and would break on a semicolon inside a string literal or a `$$` quoted Postgres function body. Flyway's `SQLiteParser` and Postgres parser exist because that problem is harder than it looks. Budget for a real parser or constrain what migrations may contain.

**Medium** on the startup cost weighting. The numbers are reliable for the configuration measured, but their significance depends entirely on whether eezo's dev reload restarts the JVM. That should be confirmed before 180 ms is treated as a deciding factor. It is the weakest of the five arguments and the recommendation does not depend on it.

**Low-medium** confidence that the down migration round trip verification (§4.2) is genuinely novel. I found no tool that does it, but absence of evidence across a survey of five tools is weaker than the other findings here.

---

## Appendix: measurement harness

All measurements ran in `/tmp/eezo-mig-bench`, outside any repository. Nothing in `eezo` or `skiff` was modified.

Dependencies resolved with `coursier fetch org.flywaydb:flyway-core:11.14.0 org.xerial:sqlite-jdbc:3.50.3.0 org.slf4j:slf4j-nop:2.0.16`.

Three migration files using the placeholder vocabulary, for example `V1__init.sql`:

```sql
create table users (
  id ${pk},
  email text not null unique,
  createdAt ${ts} not null default ${now_ts}
);
create index idx_users_email on users(email);
```

with `V2__posts.sql` exercising `${long}` and a `references ... on delete cascade`, and `V3__tags.sql` exercising a composite primary key.

Placeholder values matched skiff's `PortableSql`: `pk` = `integer primary key autoincrement`, `long` = `integer`, `ts` = `text`, `now_ts` = `(strftime('%Y-%m-%dT%H:%M:%fZ','now'))`.

The resolved SQLite schema, dumped from `sqlite_schema` after `flyway.migrate()`, confirms the technique works end to end:

```sql
CREATE TABLE users (
  id integer primary key autoincrement,
  email text not null unique,
  createdAt text not null default (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);
CREATE TABLE posts (
  id integer primary key autoincrement,
  userId integer not null references users(id) on delete cascade,
  title text not null,
  body text
);
```

The `references` clause above is present in the schema and unenforced at runtime, per §2.8.

Timing used `System.nanoTime()` around the full `Flyway.configure()...load().migrate()` call for Flyway, and around connection open plus state query plus directory listing for the own runner baseline. Cold JVM numbers are separate `java` process invocations.
