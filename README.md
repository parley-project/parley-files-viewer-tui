# parley-files-console

A full-screen terminal browser for any [parley-files](https://github.com/parley-project/parley-files)
file system: local, memory, SFTP and JDBC (and any other parley-files module on the class path).
It's the console counterpart of [parley-files-viewer](https://github.com/parley-project/parley-files-viewer):
no window system needed, so it works over ssh, in a container, or anywhere there's a terminal.

**An example of what Parley can help you build.** Like the viewer, it's an application, not part
of the Parley library: it uses parley-files the same way your own application would.

```
> [Local] /home/tony/projects                       [sftp tony@build] /srv/releases
Name                           Size  Modified       Name                       Size  Modified
../                                                 ../
parley-core/                   <DIR> 2026-10-15     parley-core-1.0.0.jar     212 K 2026-10-14
README.md                      4.1 K 2026-10-15     parley-files-1.0.0.jar    388 K 2026-10-14
README.md  4.1 K  2026-10-15 12:41
Tab pane  v view  e edit  c copy  m move  d delete  n mkdir  r rename  s search  o connect  ? help  q quit
```

Two panes, each on its own file system; copy and move work between any two of them.

- Java 11 or later, like the other Parley modules
- Built on parley-core, parley-io, parley-files, parley-files-sftp and parley-files-jdbc, and
  [Lanterna](https://github.com/mabe02/lanterna) for the text UI (pure Java, no native code)

## Running

```bash
mvn package
java -jar target/parley-files-console-1.0.0-SNAPSHOT-all.jar [left-dir] [right-dir]
```

The left pane starts in the current directory (or `left-dir`), the right one in your home
directory (or `right-dir`).

## Keys

Every command has a letter, because many terminals (macOS Terminal among them) keep function
keys for themselves; the Midnight Commander function keys work too. `?` shows this list.

| Key | Does |
|---|---|
| Up/Down, PgUp/PgDn, Home/End | move the cursor |
| Enter | open a directory; view a file |
| Backspace | parent directory |
| `[` `]` | back / forward in the pane's history |
| Tab | switch panes |
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

## How it maps to parley-files-viewer

| Viewer | Console |
|---|---|
| File table and tree | Two panes, Commander-style |
| Remote Session (connection dialog) | `o`: a form built from the factory's `ConnectionSetting`s, so every file system works without the console knowing about it |
| Copy manager with progress and cancel | Progress dialog with Cancel; asks Overwrite / Skip (/ all) when a file exists |
| Search | `s`: results fill in as they're found; Enter goes to one |
| Edit / Edit with... | `e`: a remote file is copied to a temporary local file, edited, and copied back if you saved it (with a warning if it changed on the server meanwhile) |
| New terminal at location | `t`: your shell, for local directories |
| Drag and drop, fonts, look and feel | not applicable |

## Code

The screen-free parts are separate classes, so they can be tested on their own and reused:

- `CopyJob`: copy or move files and directories between any two file systems, with progress,
  cancel and conflict handling (a move inside one file system is a rename)
- `SearchJob`: find files by name glob and/or contents
- `EditSession`: stage a remote file locally for an external editor and write it back
- `Preview`: what the viewer shows (text, or a hex dump)
- `Connections`: the installed file systems, and connecting to one
- `FileOps`: formatting and recursive delete

Directory listing, history and copy helpers come from parley-files' own toolkit-neutral
`us.bringardner.parley.files.browse` package. The UI is `ConsoleBrowser`, `FilePane` and
`ConnectDialog`. The tests drive the real UI on Lanterna's virtual terminal.

## Not done yet

- fsh in a pane's directory for remote file systems (`t` only opens a local shell today)
- A native (GraalVM) build like the viewer's
- Saved connections / recent sessions
- "Open with" another application (the viewer's Launch Services support)
