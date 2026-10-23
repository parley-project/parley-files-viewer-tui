# Changelog

## parley-files-viewer-tui 2.0.0 (unreleased)

### Changed

- **Two modules.** `parley-files-viewer-tui`: the browser on parley-term (Parley's own terminal
  code, no UI library), on its own (`pfv`) and as an fsh plugin. `parley-files-viewer-tui-lanterna`:
  the Lanterna browser, on its own (`pfv-lanterna`) and as another fsh plugin.
- **fsh plugin instead of pfsh.** Put the jar in fsh's plugin folder (`~/.parley/fsh/plugins`):
  fsh's `browse` opens this browser (`browse --with lanterna` the Lanterna one), and `view`, `edit`
  and `search` are added. `scripts/pfsh` and `FshTui` are gone, and so is this module's own
  `browse` (fsh's replaces it).
- `view` in fsh pages on parley-term.
- In fsh, a file system connected in the browser is connected in the shell, at **Connect at** (a new
  field in the connect form): it stays after the browser ends. **`D`** disconnects one of the shell's
  connections (on its own, one of the browser's). Moving a pane off a connection no longer closes it
  in fsh.
- In fsh, the panes show the shell's file tree wherever they go, so its connections are listed:
  going up from a local folder (Left, Backspace), `g`, `x`, Local in the connect list and a copy or
  move destination such as `/mem/x` all reached the local disk without them. (Both browsers.)
- `g` names the pane's file system as its header does (Local, not fileproxy). In its box, and in
  every text box, Ctrl-U clears it and Ctrl-W goes back a folder; the box says so.

## parley-files-viewer-tui 1.0.0 (unreleased)

First version: parley-files-viewer without a GUI, browsing parley-files file systems in a terminal,
in two styles from one jar.

Full screen (`pfv`):

- Two panes, each on its own file system (local, memory, SFTP, FTP, JDBC, or any registered
  parley-files module)
- View (text or hex), edit with `$VISUAL` / `$EDITOR` (remote files are staged locally and
  written back), copy and move between panes with progress and cancel, rename, new directory,
  delete, search by name and contents
- Connect form built from each file system's connection settings: only the ones that apply
  (the password or the key file, as "Log in with" changes), advanced ones behind More..., a line
  describing the field you're in, files chosen from a drop-down (`~/.ssh` to start) or with
  Browse..., and Start in for the folder to open
- Local in the connect list asks for a folder on the local disk; `x` goes back to where the pane
  last was there; leaving a connection the other pane doesn't use asks first
- Letter keys for every command, plus the Midnight Commander function keys
- One-pane layout: `w` switches, `--single` starts in it; copy and move ask for the destination

Line oriented (`pfsh`): fsh with four builtins added (fsh itself unchanged):

- `view` (full-screen pager, or printed / hex when piped), `edit` (remote files staged and
  copied back), `search` (name and contents), `browse` (the full-screen browser on the shell's
  file tree; `-c` cds to where you quit)
