# Driving the dev console without a terminal

`eezoDev` owns a terminal, so a check of it from a script needs one. `ptyrun.py` allocates a
pseudo-terminal of a given size, runs a command on it, and plays a scenario of JSON lines read
from stdin: wait for text, send keys, sleep, resize, write or copy a file, send a signal. Every
byte the command writes goes to `<transcript>.raw`; the transcript itself holds the same output
line by line with timestamps, and one line per scenario step.

```
cd examples
cp todo/src/main/scala/app/Health.scala /tmp/Health.scala.bak
python3 ../research/harnesses/dev-console/ptyrun.py /tmp/tui.txt 24 80 -- \
  sbt -error "project todo" eezoDev < ../research/harnesses/dev-console/todo-session.jsonl
```

`todo-session.jsonl` is the session the console was first checked with: boot, the four panes,
the help, a broken file, two resizes, the fix, a status command, quit. The raw transcript is a
stream of absolute cursor moves; `screens.py <transcript> <rows> <cols> [marks]` replays it
through a small emulator and prints the screen as it stood at each mark, which is how the
frames are read. A newline inside the alternate screen is the thing to look for in the raw
bytes: nothing the console writes contains one, so any that appears came from sbt, and each one
scrolls the frame.

Not a benchmark and not a suite: the console's behaviour is pinned by the plugin's own tests, and
this is the way to watch the real thing on a machine with no screen.
