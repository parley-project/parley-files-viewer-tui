# parley-files-console

Terminal (no GUI) ways to browse, copy, search and edit files on any
[parley-files](https://github.com/parley-project/parley-files) file system: local, memory, SFTP,
FTP and JDBC. It's the console counterpart of
[parley-files-viewer](https://github.com/parley-project/parley-files-viewer), and works over ssh,
in a container, or anywhere there's a terminal.

**An example of what Parley can help you build.** Like the viewer, it's an application, not part
of the Parley library: it uses parley-files the same way your own application would.

It comes in two styles, from one jar, so you can try both:

| | Command | What it is |
|---|---|---|
| Full screen | `scripts/pfc` | A Commander-style browser: two panes side by side, or one pane at full width (`w` switches, `--single` starts that way) |
| Line oriented | `scripts/pfsh` | [fsh](https://github.com/parley-project/parley-fsh), the bash-compatible shell over any file system, plus `view`, `edit`, `search` and `browse` builtins |

They share one engine: the copy, search, edit and preview code is the same, and `browse` in
`pfsh` opens the full-screen browser on the shell's own file tree (mounts included) and drops
you back at the prompt when you quit.

- Java 11 or later, like the other Parley modules
- Built on parley-files (+ sftp with its parley-ssh provider, ftp and jdbc), fsh, and [Lanterna](https://github.com/mabe02/lanterna)
  for the full-screen UI (pure Java, no native code)

## Building and running

```bash
mvn package
scripts/pfc [--single] [left-dir] [right-dir]      # full screen
scripts/pfsh                                       # fsh with the extra builtins
```

Or without the scripts:

```bash
java -jar target/parley-files-console-1.0.0-SNAPSHOT-all.jar [--single] [left-dir] [right-dir]
java -cp target/parley-files-console-1.0.0-SNAPSHOT-all.jar us.bringardner.parley.files.console.fsh.FshConsole
```

`pfsh` uses JNA for fsh's terminal handling. If you have fsh's native library and `fshexec`
(parley-fsh/macos), set `FSH_LIBDIR` to that folder and pfsh uses them, as fsh's own launcher
does.

## The full-screen browser (pfc)

```
> [Local] /home/tony/projects                       [sftp://tony@build:22] /srv/releases
Name                           Size  Modified       Name                       Size  Modified
../                                                 ../
parley-core/                   <DIR> 2026-10-15     parley-core-1.0.0.jar     212 K 2026-10-14
README.md                      4.1 K 2026-10-15     parley-files-1.0.0.jar    388 K 2026-10-14
README.md  4.1 K  2026-10-15 12:41
Tab pane  w layout  v view  e edit  c copy  m move  d delete  n mkdir  r rename  s search  ? help  q quit
```

Each pane can be on its own file system; copy and move work between any two of them. In the
one-pane layout the second location is still there: Tab switches between them, and copy and
move ask for the destination (suggesting the other location).

Every command has a letter, because many terminals (macOS Terminal among them) keep function
keys for themselves; the Midnight Commander function keys work too. `?` shows this list.

| Key | Does |
|---|---|
| Up/Down, PgUp/PgDn, Home/End | move the cursor |
| Enter | open a directory; view a file |
| Backspace | parent directory |
| `[` `]` | back / forward in the pane's history |
| Tab | switch panes (one pane: switch locations) |
| `w` | one pane / two panes |
| `g` | go to a path |
| `=` | show this pane's directory in the other pane |
| Space, Insert | mark / unmark |
| `a` | mark all / clear marks |
| `v` F3 | view (text, or hex for binary files; the first 1 MB) |
| `e` F4 | edit with `$VISUAL` / `$EDITOR` (else vi, notepad on Windows) |
| `c` F5 | copy to the other pane |
| `m` F6 | move to the other pane |
| `r` | rename |
| `n` F7 | new directory |
| `d` F8 Delete | delete |
| `s` | search below this directory, by name (glob) and/or text |
| `o` | connect this pane to a file system |
| `x` | disconnect: back to the local home directory |
| `t` | shell in this directory (local file systems) |
| `h` | show / hide hidden files |
| `f` | sort by name, size, modified |
| Ctrl-R | refresh |
| `q` F10 | quit |

Commands act on the marked entries, or on the one under the cursor when nothing is marked.

## fsh with the browser's builtins (pfsh)

Everything fsh does (`connect`, `cd`, `ls`, `cp`, `find`, pipes, scripts, completion; see its
[COMMANDS.md](https://github.com/parley-project/parley-fsh/blob/master/COMMANDS.md)), plus:

| Builtin | Does |
|---|---|
| `view [-c] [-x] FILE ...` | full-screen pager; prints instead (hex for binary) when piped or with `-c` |
| `edit FILE` | `$VISUAL` / `$EDITOR`; a file on a mounted remote file system is staged locally and copied back if you saved, with a warning if it changed on the server meanwhile |
| `search [-i] [-a] [-l] [-n NAME] [-t TEXT] [DIR ...]` | by name glob and/or contents, on any mounted file system; prints paths as `find` does |
| `browse [-1] [-c] [DIR [DIR2]]` | the full-screen browser on the shell's file tree; `-c` cds to where you quit |

`help NAME` describes each. For example:

```bash
connect sftp host=build user=tony password=@keychain:parley/sftp/tony@build /build
search -i -t 'TODO' /build/src
edit /build/etc/app.properties
browse -c /build
```

The builtins are added to fsh's command table before it starts; fsh itself isn't changed.

## How it maps to parley-files-viewer

| Viewer | pfc | pfsh |
|---|---|---|
| File table and tree | One or two panes | `ls`, `cd`, `browse` |
| Remote Session (connection dialog) | `o`: a form built from the factory's `ConnectionSetting`s, so every file system works | `connect` |
| Copy manager (progress, cancel) | Progress dialog with Cancel; asks Overwrite / Skip (/ all) | `cp`, `mv` |
| Search | `s` | `search` |
| Edit / Edit with... | `e` | `edit` |
| New terminal at location | `t` (local) | it's a shell |
| Drag and drop, fonts, look and feel | not applicable | not applicable |

## Code

The screen-free parts are separate classes, so both styles share them and they're tested on
their own:

- `CopyJob`: copy or move files and directories between any two file systems, with progress,
  cancel and conflict handling (a move inside one file system is a rename)
- `SearchJob`: find files by name glob and/or contents
- `EditSession`: stage a remote file locally for an external editor and write it back
- `Preview`: what view shows (text, or a hex dump)
- `Connections`: the installed file systems, and connecting to one
- `FileOps`: formatting, labels and recursive delete

The full-screen UI is `ConsoleBrowser`, `FilePane`, `ConnectDialog` and `Tui`; the fsh builtins
are in `us.bringardner.parley.files.console.fsh` (`FshConsole` is the launcher). The tests drive
the full-screen UI on Lanterna's virtual terminal and run the builtins in a real fsh.

## Not done yet

- `t` in pfc opens your shell only for local directories (pfsh's `browse` covers the other way)
- A native (GraalVM) build like the viewer's
- Saved connections / recent sessions in pfc
- "Open with" another application (the viewer's Launch Services support)
