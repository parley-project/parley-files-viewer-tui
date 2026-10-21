# Changelog

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
