#!/usr/bin/env python3
"""Run a command under a pseudo-terminal and drive it with a scripted scenario.

usage: ptyrun.py <transcript-file> <rows> <cols> -- <cmd...>
scenario steps are read from stdin as JSON lines:
  {"expect": "text", "timeout": 60}   wait until text appears in output
  {"send": "q"}                        write bytes (JSON string, \\u001b for ESC)
  {"sleep": 1.5}
  {"resize": [rows, cols]}             TIOCSWINSZ + SIGWINCH
  {"write": ["path", "content"]}       write a file (edit a source)
  {"append": ["path", "content"]}
  {"signal": "INT"}                    kill -INT the child
  {"mark": "label"}                    timestamped marker in the transcript
All output is appended to the transcript with timestamps; the raw stream also goes to <transcript>.raw
"""
import sys, os, pty, select, struct, fcntl, termios, signal, json, time, subprocess

def main():
    args = sys.argv[1:]
    transcript, rows, cols = args[0], int(args[1]), int(args[2])
    assert args[3] == '--'
    cmd = args[4:]
    steps = [json.loads(l) for l in sys.stdin if l.strip()]
    master, slave = pty.openpty()
    fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
    env = dict(os.environ, TERM='xterm-256color', LANG='C.UTF-8', LC_ALL='C.UTF-8')
    p = subprocess.Popen(cmd, stdin=slave, stdout=slave, stderr=slave, preexec_fn=os.setsid, env=env, close_fds=True)
    os.close(slave)
    raw = open(transcript + '.raw', 'wb')
    log = open(transcript, 'w')
    t0 = time.time()
    buf = b''
    def note(s):
        log.write('[%7.2f] %s\n' % (time.time() - t0, s)); log.flush()
    def pump(timeout):
        nonlocal buf
        r, _, _ = select.select([master], [], [], timeout)
        if master in r:
            try:
                data = os.read(master, 65536)
            except OSError:
                return False
            if not data:
                return False
            raw.write(data); raw.flush()
            buf += data
            for line in data.decode('utf-8', 'replace').splitlines():
                note('OUT ' + line)
        return True
    alive = True
    for step in steps:
        if not alive: break
        if 'expect' in step:
            deadline = time.time() + step.get('timeout', 60)
            needle = step['expect'].encode()
            note('EXPECT %r' % step['expect'])
            while needle not in buf:
                if time.time() > deadline:
                    note('TIMEOUT waiting for %r' % step['expect']); break
                if not pump(0.2):
                    if p.poll() is not None: alive = False; note('EXITED %s' % p.returncode); break
            else:
                buf = buf[buf.find(needle) + len(needle):]
                note('FOUND %r' % step['expect'])
        elif 'send' in step:
            note('SEND %r' % step['send']); os.write(master, step['send'].encode())
        elif 'sleep' in step:
            end = time.time() + step['sleep']
            while time.time() < end:
                if not pump(min(0.2, max(0, end - time.time()))) and p.poll() is not None:
                    alive = False; note('EXITED %s' % p.returncode); break
        elif 'resize' in step:
            r, c = step['resize']
            fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', r, c, 0, 0))
            os.killpg(p.pid, signal.SIGWINCH); note('RESIZE %dx%d' % (r, c))
        elif 'write' in step:
            path, content = step['write']; open(path, 'w').write(content); note('WROTE %s at %d' % (path, int(time.time()*1000)))
        elif 'append' in step:
            path, content = step['append']; open(path, 'a').write(content); note('APPENDED %s at %d' % (path, int(time.time()*1000)))
        elif 'copy' in step:
            src, dst = step['copy']; open(dst, 'w').write(open(src).read()); note('COPIED %s -> %s at %d' % (src, dst, int(time.time()*1000)))
        elif 'signal' in step:
            os.killpg(p.pid, getattr(signal, 'SIG' + step['signal'])); note('SIGNAL %s' % step['signal'])
        elif 'mark' in step:
            note('MARK %s' % step['mark'])
    # drain until exit or 20s
    end = time.time() + 20
    while time.time() < end and p.poll() is None:
        pump(0.5)
    if p.poll() is None:
        note('KILLING'); os.killpg(p.pid, signal.SIGKILL)
    while pump(0.2): pass
    note('EXIT %s' % p.poll())
    log.close(); raw.close()

if __name__ == '__main__':
    main()
