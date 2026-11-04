#!/usr/bin/env python3
"""Replays a ptyrun transcript through a small terminal emulator and prints the screen at each
mark: `screens.py <transcript> <rows> <cols> [mark,mark,...]`. Cursor moves, erases, scrolling
on a newline and wrapping are honoured, colour is dropped, a resize is not followed."""
import re,sys
path=sys.argv[1]; H=int(sys.argv[2]); W=int(sys.argv[3])
want=set(sys.argv[4].split(',')) if len(sys.argv)>4 else None
grid=[[' ']*W for _ in range(H)]; r=c=0
def put(ch):
    global r,c
    if c>=W:
        c=0; r+=1
        if r>=H: grid.pop(0); grid.append([' ']*W); r=H-1
    grid[r][c]=ch; c+=1
def feed(s):
    global r,c,grid,H,W
    i=0
    while i<len(s):
        ch=s[i]
        if ch=='\x1b' and i+1<len(s) and s[i+1]=='[':
            j=i+2
            while j<len(s) and not ('@'<=s[j]<='~'): j+=1
            if j>=len(s): return
            params=s[i+2:j]; fin=s[j]; i=j+1
            if fin=='H' or fin=='f':
                p=[int(x) if x else 1 for x in params.split(';')] if params else [1,1]
                if len(p)==1: p.append(1)
                r=min(H-1,max(0,p[0]-1)); c=min(W-1,max(0,p[1]-1))
            elif fin=='K': 
                for k in range(c,W): grid[r][k]=' '
            elif fin=='J':
                if params in ('','0'):
                    for k in range(c,W): grid[r][k]=' '
                    for rr in range(r+1,H): grid[rr]=[' ']*W
                elif params=='2':
                    grid=[[' ']*W for _ in range(H)]
            elif fin=='m' or params.startswith('?'): pass
            elif fin=='A': r=max(0,r-(int(params or 1)))
            elif fin=='D': c=max(0,c-(int(params or 1)))
            continue
        if ch=='\r': c=0
        elif ch=='\n':
            r+=1
            if r>=H: grid.pop(0); grid.append([' ']*W); r=H-1
        elif ch=='\x1b': pass
        elif ord(ch)>=32: put(ch)
        i+=1
def show(name):
    print(f"===== {name}")
    for row in grid: print('|'+''.join(row).rstrip()+'|')
for line in open(path,encoding='utf-8',errors='replace'):
    m=re.match(r'\[\s*[\d.]+\] (OUT|MARK) ?(.*)$', line.rstrip('\n'))
    if not m: continue
    if m.group(1)=='OUT': feed(m.group(2))
    elif want is None or m.group(2) in want: show(m.group(2))
