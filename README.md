# parley-files-viewer-tui

[parley-files-viewer](https://github.com/parley-project/parley-files-viewer) without a GUI: browse,
copy, search and edit files on any [parley-files](https://github.com/parley-project/parley-files)
file system (local, memory, SFTP, FTP and JDBC) in a terminal. It works over ssh, in a container, or
anywhere there's a terminal and no display.

**An example of what Parley can help you build.** Like the viewer, it's an application, not part
of the Parley library: it uses parley-files the same way your own application would.

It comes two ways:

| | How | What it is |
|---|---|---|
| On its own | `scripts/pfv` | A Commander-style browser: two panes side by side, or one pane at full width (`w` switches, `--single` starts that way) |
| In fsh | the jar in fsh's plugin folder | [fsh](https://github.com/parley-project/parley-fsh), the bash-compatible shell over any file system, gets the browser for its `browse` command, and `view`, `edit` and `search` |

Both use one engine: the copy, search, edit and preview code is the same, and `browse` in fsh
opens the browser on the shell's own file tree (connections included) and drops you back at the prompt
when you quit.

The browser is built on [parley-term](https://github.com/parley-project/parley-term), Parley's
own terminal code, with no UI library. The repository has a second module,
`parley-files-viewer-tui-lanterna`: the same browser on [Lanterna](https://github.com/mabe02/lanterna),
on its own (`scripts/pfv-lanterna`) or as another fsh plugin (`browse --with lanterna`).

- Java 11 or later, like the other Parley modules
- Built on parley-files (+ sftp with its parley-ssh provider, ftp and jdbc) and parley-term; fsh
  for the plugin

## Building and running

```bash
mvn package
scripts/pfv [--single] [left-dir] [right-dir]            # on its own
scripts/pfv-lanterna [--single] [left-dir] [right-dir]   # the Lanterna one
```

Or without the scripts:

```bash
java -jar parley-files-viewer-tui/target/parley-files-viewer-tui-2.0.0-SNAPSHOT-all.jar [--single] [left-dir] [right-dir]
java -jar parley-files-viewer-tui-lanterna/target/parley-files-viewer-tui-lanterna-2.0.0-SNAPSHOT-all.jar [--single] [left-dir] [right-dir]
```

### As an fsh plugin

Copy the jars to one of fsh's plugin folders (yours is `~/.parley/fsh/plugins`; `plugins` in fsh
lists them all):

```bash
mkdir -p ~/.parley/fsh/plugins
cp parley-files-viewer-tui/target/parley-files-viewer-tui-2.0.0-SNAPSHOT.jar ~/.parley/fsh/plugins/
# and, for browse --with lanterna, the Lanterna one (it needs the jar above too):
cp parley-files-viewer-tui-lanterna/target/parley-files-viewer-tui-lanterna-2.0.0-SNAPSHOT-plugin.jar ~/.parley/fsh/plugins/
```

The plain jars are the plugins: fsh has parley-files and parley-term already. The `-all` jars are
for running on their own.

## The full-screen browser (pfv)

```
> [Local] /home/tony/projects                       [sftp://tony@build:22] /srv/releases
Name                           Size  Modified       Name                       Size  Modified
../                                                 ../
parley-core/                   <DIR> 2026-10-15     parley-core-1.0.0.jar     212 K 2026-10-14
README.md                      4.1 K 2026-10-15     parley-files-1.0.0.jar    388 K 2026-10-14
README.md  4.1 K  2026-10-15 12:41
Tab pane  w layout  v view  e edit  c copy  m move  d delete  n mkdir  r rename  s search  ? help  q quit
```

The browser has one file tree, as fsh's shell does: the local disk, and each file system you
connect (`o`) at the path in the connect form's **Connect at** field (`/sftp-build`, say). A
path means one place whichever pane you're in, `g` and copy destinations take any path in the
tree, and going up from a connection leads back to the folder it's in. Connections stay until
`D` disconnects one or you quit, which closes them all.

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
| `g` | go to a path (in fsh, in the shell's file tree, connections included); in the box, Ctrl-U clears it and Ctrl-W goes back a folder |
| `=` | show this pane's directory in the other pane |
| Space, Insert | mark / unmark |
| Shift+Up / Down (PgUp, PgDn, Home, End) | select from where you start to here, as in a GUI list; going back unselects, and a move without Shift drops the selection (Space marks stay). macOS Terminal sends Shift+Up / Down as plain Up / Down until its profile's Keyboard settings send `\033[1;2A` and `\033[1;2B` |
| `a` | mark all / clear marks |
| `v` F3 | view (text, or hex for binary files; the first 1 MB) |
| `e` F4 | edit with `$VISUAL` / `$EDITOR` (else vi, notepad on Windows) |
| `c` F5 | copy to the other pane |
| `m` F6 | move to the other pane |
| Ctrl-C / Ctrl-X, then Ctrl-V | copy / cut the marked entries (else the one under the cursor), then paste them in the directory you're in, in either pane (a cut is moved once) |
| `r` | rename |
| `n` F7 | new directory |
| `d` F8 Delete | delete |
| `s` | search below this directory, by name (glob) and/or text |
| `o` | connect this pane to a file system, or Local to choose a folder on the local disk |
| `x` | back to the local disk, where this pane last was there (else home) |
| `t` | shell in this directory (local file systems) |
| `h` | show / hide hidden files |
| `f` | sort by name, size, modified |
| Ctrl-R | refresh |
| `q` F10 | quit |

Commands act on the marked entries, or on the one under the cursor when nothing is marked.

In any text box, Ctrl-U clears it and Ctrl-W deletes back to the previous `/` or space (in a
path, back a folder).

The connect form shows only the settings that apply: "Log in with" shows the password field, or
the key file's, as it changes, and the advanced settings are behind More.... The line under the
form describes the field you're in. A file (a private key, known hosts) can be typed, chosen
from a drop-down of the files in that folder (`~/.ssh` to start; Enter opens it), or found with
Browse.... Start in says which folder to open once connected, and Connect at where in the tree
the file system goes.

## In fsh (the plugin)

Everything fsh does (`connect`, `cd`, `ls`, `cp`, `find`, pipes, scripts, completion; see its
[COMMANDS.md](https://github.com/parley-project/parley-fsh/blob/master/COMMANDS.md)), plus:

| Command | Does |
|---|---|
| `browse [-1] [-c] [DIR [DIR2]]` | fsh's own command: with this plugin, the full-screen browser on the shell's file tree; `-c` cds to where you quit; `browse --with lanterna` uses the Lanterna one |
| `view [-c] [-x] FILE ...` | full-screen pager; prints instead (hex for binary) when piped or with `-c` |
| `edit FILE` | `$VISUAL` / `$EDITOR`; a file on a connected remote file system is staged locally and copied back if you saved, with a warning if it changed on the server meanwhile |
| `search [-i] [-a] [-l] [-n NAME] [-t TEXT] [DIR ...]` | by name glob and/or contents, on any connected file system; prints paths as `find` does |

`help NAME` describes each. For example:

```bash
connect sftp host=build user=tony password=@keychain:parley/sftp/tony@build /build
search -i -t 'TODO' /build/src
edit /build/etc/app.properties
browse -c /build
```

In fsh, a file system you connect in the browser (`o`) is connected in the shell too, at the
path in the form's **Connect at** field (`/sftp-build`, say): it stays when you quit, `ls`, `cd`
and `browse -c` reach it, and fsh's `disconnect` closes it. `D` in the browser lists the shell's
connections and disconnects one. It's the same tree `pfv` has on its own, except that the
shell's outlives the browser.

fsh finds them in its plugin folder as it starts (its `CommandProvider` and `BrowserProvider`); fsh itself has no dependency on this module.

## How it maps to parley-files-viewer

| Viewer | pfv | fsh |
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

The full-screen UI is `termui` (`TermBrowser`, `TermPane`, `ConnectForm`, `TermMain`), on
parley-term; the fsh plugin is `us.bringardner.parley.files.viewer.tui.fsh` (`ViewerCommands`,
`TerminalBrowser`). The Lanterna module's UI is `TuiBrowser`, `FilePane`, `ConnectDialog` and
`Tui`, with `LanternaBrowser` for fsh. The tests drive each UI on a virtual terminal and run the
commands in a real fsh.

## Not done yet

- `t` in pfv opens your shell only for local directories (fsh's `browse` covers the other way)
- A native (GraalVM) build like the viewer's
- Saved connections / recent sessions in pfv
- "Open with" another application (the viewer's Launch Services support)
