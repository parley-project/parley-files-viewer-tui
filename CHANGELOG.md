# Changelog

## parley-files-console 1.0.0 (unreleased)

First version: a full-screen terminal browser for parley-files file systems, the console
counterpart of parley-files-viewer.

- Two panes, each on its own file system (local, memory, SFTP, JDBC, or any registered
  parley-files module)
- View (text or hex), edit with `$VISUAL` / `$EDITOR` (remote files are staged locally and
  written back), copy and move between panes with progress and cancel, rename, new directory,
  delete, search by name and contents
- Connect form built from each file system's connection settings
- Letter keys for every command, plus the Midnight Commander function keys
