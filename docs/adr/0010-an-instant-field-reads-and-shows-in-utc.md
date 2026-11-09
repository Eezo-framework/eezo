# An Instant field reads and shows in UTC

**Status:** accepted

A `datetime-local` input holds a date and a time with no zone, and a browser refuses a value that ends in `Z`. So `Field[Instant]` has to pick the zone that turns the input's text into an instant and back. It picks UTC. The input holds the UTC text, such as `2026-10-13T10:00`, and a show page and an index print the same text followed by ` UTC`, through `Field.display`. The input keeps the text without the zone, because the browser refuses any suffix.

The reason is that two servers read the same text as the same instant. The choice needs no setting and no script in the browser, and UTC has no gap or overlap when the clocks change.

## Considered options

**The server's zone.** The time a user types near a single server is the time they mean. Rejected because two servers in two zones read the same text as two instants, and a time in the spring gap or the autumn overlap needs a rule of its own.

**The browser's zone, sent by a script.** Rejected because a derived form must work with no script, and the zone would be one more value the browser sends and the user can change.

**No marker on the pages.** Rejected because the pages printed `Z` before this change, and a page that prints `2026-10-13T10:00` alone reads as local time.

## Consequences

A user in another zone types and reads UTC. An application that wants local time writes its own `Field[Instant]`, and that Field can give its pages their own text through `display`. `Field` gains one optional constructor value, the text a page prints, which defaults to `show`.
