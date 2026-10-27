/**
 * <PRE>
 *
 * Copyright Tony Bringardner 2026 <A href="http://bringardner.com/tony">Tony Bringardner</A>
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *
 *	@author Tony Bringardner
 */
package us.bringardner.parley.files.viewer.tui.termui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.browse.FileEntry;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.viewer.tui.Connections;
import us.bringardner.parley.files.viewer.tui.CopyJob;
import us.bringardner.parley.files.viewer.tui.EditSession;
import us.bringardner.parley.files.viewer.tui.FileOps;
import us.bringardner.parley.files.viewer.tui.Preview;
import us.bringardner.parley.files.viewer.tui.SearchJob;
import us.bringardner.parley.term.screen.Dialog;
import us.bringardner.parley.term.screen.Field;
import us.bringardner.parley.term.tty.Key;
import us.bringardner.parley.term.screen.ListView;
import us.bringardner.parley.term.screen.Rect;
import us.bringardner.parley.term.screen.Screen;
import us.bringardner.parley.term.screen.Theme;
import us.bringardner.parley.term.screen.Tui;

/**
 * The browser on Parley's own terminal code (parley-term),
 * with no third-party library, and the Lanterna version's features and keys. Two panes (or
 * one, w), each on any file system: view, edit, copy, move, rename, mkdir, delete, search,
 * connect (the form built from each file system's settings), the local disk, a shell, history,
 * marks, hidden files and sorting. The engine underneath is the same (CopyJob, SearchJob,
 * EditSession, Preview, Connections).
 *
 * The whole screen is drawn from this object's state after each event ({@link #render}),
 * so the state is all here and is changed only on the UI thread: work that takes time runs on
 * {@code worker} and hands its result to {@code ui}. Dialogs are a stack over the panes.
 */
public class TermBrowser implements Tui.App {

	/** What the browser needs from whoever runs it. */
	public interface Host {
		/** Give the terminal to a program (an editor, a shell) and take it back when it exits. */
		int runProgram(List<String> command, File dir) throws IOException;

		/** the file tree of the program the browser runs in (fsh's), or null: the browser makes its own */
		default us.bringardner.parley.files.viewer.tui.HostConnections connections() {
			return null;
		}

	}

	static final String[] KEY_BAR = {"? help", "q quit", "Tab pane", "w layout", "v view", "e edit", "c copy", "m move",
			"d delete", "n mkdir", "r rename", "s search", "o connect", "t shell", "g goto", "h hidden", "f sort"};

	static final String HELP = String.join("\n",
			"Moving around",
			"  Up/Down PgUp/PgDn Home/End   move the cursor (with Shift: mark from where you start to here)",
			"  Enter                        open a directory, view a file",
			"  Backspace                    parent directory",
			"  [ / ]                        back / forward in this pane's history",
			"  Tab                          switch panes (one pane: switch between the two locations)",
			"  w                            one pane / two panes",
			"  g                            go to a path (in it: Ctrl-U clears, Ctrl-W goes back a folder)",
			"  = (equals)                   show this pane's directory in the other pane",
			"",
			"Marking",
			"  Space or Insert              mark / unmark and move down",
			"  a                            mark all / clear marks",
			"",
			"Files (marked ones, else the one under the cursor)",
			"  v  F3                        view",
			"  e  F4                        edit with $VISUAL / $EDITOR (remote files are copied",
			"                               here, edited, and copied back if you saved)",
			"  c  F5                        copy to the other pane's directory (one pane: asks where)",
			"  m  F6                        move to the other pane's directory (one pane: asks where)",
			"  Ctrl-C / Ctrl-X, then Ctrl-V copy / cut, then paste in the directory you're in",
			"  r                            rename",
			"  n  F7                        new directory",
			"  d  F8  Delete                delete",
			"",
			"Other",
			"  s                            search below this directory (name and/or text)",
			"  o                            connect this pane to a file system (SFTP, JDBC, ...), or Local",
			"  x                            back to the local disk, where this pane last was there",
			"  D                            disconnect the connection under the cursor (elsewhere: choose one)",
			"  t                            shell in this directory (local file systems)",
			"  h                            show / hide hidden files",
			"  f                            sort by name, size, modified (the column is marked \u25b2 / \u25bc)",
			"  F                            reverse the order (Z-A, smallest first, oldest first)",
			"  Ctrl-R                       refresh",
			"  ?  F1                        this help",
			"  q  F10                       quit");

	private final Executor worker;
	private final Executor ui;
	private final Host host;
	/**
	 * The file tree the panes are in: the shell's in fsh; else the browser's own (pfv), whose
	 * connections close when it quits. Either way a path means one place, connections included.
	 */
	private final us.bringardner.parley.files.viewer.tui.HostConnections connections;
	private final us.bringardner.parley.files.viewer.tui.TreeConnections ownTree;
	private final TermPane[] panes = new TermPane[2];
	private final Dialog.Stack dialogs = new Dialog.Stack();
	private int active;
	private boolean single;
	private String status = "";
	private boolean done;

	/**
	 * @param left   where the first pane starts
	 * @param right  where the second pane starts
	 * @param single start with one pane
	 * @param worker runs listing, reading, copying, searching, connecting
	 * @param ui     runs a result on the UI thread and draws the screen again
	 */
	public TermBrowser(FileSource left, FileSource right, boolean single, Executor worker, Executor ui, Host host) {
		this.worker = worker;
		this.ui = ui;
		this.host = host;
		us.bringardner.parley.files.viewer.tui.HostConnections given = host.connections();
		if( given != null ) {
			connections = given;
			ownTree = null;
		} else {
			try {
				ownTree = new us.bringardner.parley.files.viewer.tui.TreeConnections();
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
			connections = ownTree;
		}
		this.single = single;
		for(int i=0; i < 2; i++) {
			int idx = i;
			panes[i] = new TermPane(worker, ui, text->{
				if( idx == active ) {
					setStatus(text);
				}
			});
		}
		for(TermPane p : panes) {
			p.setFolders(this::inHost);
			p.setConnections(connections::list);
		}
		panes[0].go(left);
		panes[1].go(right);
		setActive(0);
	}

	// ---------------------------------------------------------------- state

	public FileSource getActiveDirectory() {
		return pane().getDirectory();
	}

	public FileSource getDirectory(int idx) {
		return panes[idx].getDirectory();
	}

	public String getStatus() {
		return status;
	}

	public boolean isSingle() {
		return single;
	}

	public int getActive() {
		return active;
	}

	/** Whether a dialog (or a full-screen view) has the keyboard. */
	public boolean hasDialog() {
		return !dialogs.isEmpty();
	}

	Dialog topDialog() {
		return dialogs.top();
	}

	private TermPane pane() {
		return panes[active];
	}

	private TermPane other() {
		return panes[1-active];
	}

	private void setActive(int idx) {
		active = idx;
		panes[idx].setActive(true);
		panes[1-idx].setActive(false);
		setStatus(panes[idx].describeCurrent());
	}

	void setStatus(String text) {
		int nl = text == null ? -1 : text.indexOf('\n');
		status = text == null ? "" : nl >= 0 ? text.substring(0, nl)+" ..." : text;
	}

	private void error(String what, Exception e) {
		String msg = what+": "+(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		ui.execute(()->setStatus(msg));
	}

	// ---------------------------------------------------------------- keys

	@Override
	public boolean isDone() {
		return done;
	}

	/** End the session. */
	public void quit() {
		done = true;
		if( ownTree != null ) {
			// the browser's own connections end with it (fsh's stay: they're the shell's)
			ownTree.disconnectAll();
		}
	}

	/** One key; true when the screen should be drawn again. */
	@Override
	public boolean key(Key k) {
		if( hasDialog()) {
			dialogs.key(k);
			return true;
		}
		return command(k);
	}

	private boolean command(Key k) {
		if( k.isCtrl('r')) {
			pane().refresh();
			return true;
		}
		if( k.isPlainChar()) {
			switch (k.ch) {
				case 'q': quit(); return true;
				case 'w': single = !single; return true;
				case '?': help(); return true;
				case 'v': viewSelected(); return true;
				case 'e': edit(); return true;
				case 'c': copy(false); return true;
				case 'm': copy(true); return true;
				case 'r': rename(); return true;
				case 'n': mkdir(); return true;
				case 'd': delete(); return true;
				case 's': search(); return true;
				case 'o': connect(); return true;
				case 'x': disconnect(); return true;
				case 'D': disconnectKey(); return true;
				case 't': shell(); return true;
				case 'g': gotoPath(); return true;
				case 'h': pane().setShowHidden(!pane().isShowHidden()); return true;
				case 'f': pane().nextSort(); return true;
				case 'F': pane().reverseSort(); return true;
				case 'a': pane().toggleMarkAll(); return true;
				case ' ': pane().toggleMark(); return true;
				case '[': pane().back(); return true;
				case ']': pane().forward(); return true;
				case '=': other().go(pane().getDirectory()); return true;
				default: return false;
			}
		}
		// the clipboard: Ctrl-C copies or Ctrl-X cuts the marked files (else the one under the
		// cursor); Ctrl-V, in another directory (either pane), copies or moves them there
		if( k.isCtrl('c')) {
			clip(false);
			return true;
		}
		if( k.isCtrl('x')) {
			clip(true);
			return true;
		}
		if( k.isCtrl('v')) {
			paste();
			return true;
		}
		switch (k.code) {
			case TAB: case BACKTAB: setActive(1-active); return true;
			case BACKSPACE: case LEFT: pane().up(); return true;
			case INSERT: pane().toggleMark(); return true;
			case DELETE: case F8: delete(); return true;
			case F1: help(); return true;
			case F3: viewSelected(); return true;
			case F4: edit(); return true;
			case F5: copy(false); return true;
			case F6: copy(true); return true;
			case F7: mkdir(); return true;
			case F10: quit(); return true;
			// with Shift: mark from where the Shift selection started to here
			case UP: if( k.shift ) pane().extendBy(-1); else pane().moveBy(-1); return true;
			case DOWN: if( k.shift ) pane().extendBy(1); else pane().moveBy(1); return true;
			case PAGE_UP: if( k.shift ) pane().extendBy(-pane().pageRows()); else pane().pageUp(); return true;
			case PAGE_DOWN: if( k.shift ) pane().extendBy(pane().pageRows()); else pane().pageDown(); return true;
			case HOME: if( k.shift ) pane().extendTo(0); else pane().moveTo(0); return true;
			case END: if( k.shift ) pane().extendTo(pane().rows()-1); else pane().end(); return true;
			case ENTER: case RIGHT: open(); return true;
			default: return false;
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void render(Screen screen) {
		Rect area = screen.area();
		Rect panesArea = area.rows(0, area.height-2);
		if( single ) {
			pane().render(screen, panesArea, "["+(active+1)+"/2]");
		} else {
			int left = (area.width-1)/2;
			panes[0].render(screen, panesArea.cols(0, left), null);
			panes[1].render(screen, panesArea.cols(left+1, area.width-left-1), null);
		}
		screen.line(area.row(area.height-2), status, Theme.NORMAL);
		screen.line(area.row(area.height-1), keyBar(area.width, pane().connectionUnderCursor() != null), Theme.HINT);
		dialogs.render(screen);
	}

	/** As many of the keys as fit. */
	static String keyBar(int width) {
		return keyBar(width, false);
	}

	/** As many of the keys as fit; with D disconnect after o connect when the cursor is on a connection. */
	static String keyBar(int width, boolean onConnection) {
		StringBuilder b = new StringBuilder();
		List<String> keys = new ArrayList<>(Arrays.asList(KEY_BAR));
		if( onConnection ) {
			keys.add(keys.indexOf("o connect")+1, "D disconnect");
		}
		for(String k : keys) {
			if( b.length()+k.length()+2 > width ) {
				break;
			}
			b.append(b.length() == 0 ? "" : "  ").append(k);
		}
		return b.toString();
	}

	// ---------------------------------------------------------------- commands

	private void help() {
		dialogs.open(new Dialog.TextScreen("Keys", HELP));
	}

	private void open() {
		if( pane().onParent()) {
			pane().up();
			return;
		}
		FileEntry e = pane().current();
		if( e != null && e.isDirectory()) {
			pane().go(e.getFile());
		} else if( e != null ) {
			view(e.getFile());
		}
	}

	private void viewSelected() {
		FileEntry e = pane().current();
		if( e == null || e.isDirectory()) {
			setStatus("Choose a file to view");
			return;
		}
		view(e.getFile());
	}

	private void view(FileSource file) {
		setStatus("Reading "+file.getName()+" ...");
		worker.execute(()->{
			try {
				Preview p = Preview.of(file);
				ui.execute(()->{
					String title = file.getName()+(p.isBinary() ? " (hex)" : "")
							+(p.isTruncated() ? " (first "+FileOps.formatSize(Preview.DEFAULT_LIMIT)+")" : "");
					dialogs.open(new Dialog.TextScreen(title, p.getText()));
					setStatus(pane().describeCurrent());
				});
			} catch (IOException e) {
				error("Can't read "+file.getName(), e);
			}
		});
	}

	private void edit() {
		FileEntry e = pane().current();
		if( e == null || e.isDirectory()) {
			setStatus("Choose a file to edit");
			return;
		}
		FileSource file = e.getFile();
		EditSession session;
		try {
			session = EditSession.open(file);
		} catch (IOException ex) {
			error("Can't edit "+file.getName(), ex);
			return;
		}
		int code;
		try {
			code = host.runProgram(EditSession.editorCommand(session.getLocalFile()), session.getLocalFile().getParentFile());
		} catch (IOException ex) {
			session.close();
			error("Can't run the editor", ex);
			return;
		}
		try {
			if( !session.isRemote()) {
				setStatus(code == 0 ? "Edited "+file.getName() : "Editor exited with "+code);
				session.close();
			} else if( !session.isChanged()) {
				setStatus(file.getName()+" not changed");
				session.close();
			} else {
				Runnable save = ()->{
					try {
						session.writeBack();
						setStatus("Saved "+file.getName()+" to "+FileOps.describe(file.getFileSourceFactory()));
					} catch (IOException ex) {
						error("Can't save "+file.getName(), ex);
					} finally {
						session.close();
					}
					pane().refreshQuietly();
				};
				if( session.isRemoteChanged()) {
					dialogs.open(new Dialog.Confirm("Changed elsewhere", file.getName()+" was changed on "
							+file.getFileSourceFactory().getTypeId()+" while you edited it. Replace it with your version?",
							save, ()->{
								setStatus("Your edit of "+file.getName()+" was not saved");
								session.close();
							}));
				} else {
					save.run();
				}
			}
		} catch (IOException ex) {
			session.close();
			error("Can't edit "+file.getName(), ex);
		}
		pane().refreshQuietly();
	}

	private void shell() {
		FileSource dir = pane().getDirectory();
		if( !FileOps.isLocal(dir)) {
			setStatus("A shell needs a local directory");
			return;
		}
		try {
			int code = host.runProgram(EditSession.shellCommand(), FileOps.toLocalFile(dir));
			setStatus("Shell exited"+(code == 0 ? "" : " with "+code));
		} catch (IOException e) {
			error("Can't run a shell", e);
		}
		pane().refreshQuietly();
		other().refreshQuietly();
	}

	/** what Ctrl-C copied or Ctrl-X cut, for Ctrl-V */
	private List<FileSource> clipboard = new ArrayList<>();
	private boolean clipboardCut;

	private void clip(boolean cut) {
		List<FileSource> files = pane().selection();
		if( files.isEmpty()) {
			setStatus("Nothing to "+(cut ? "cut" : "copy"));
			return;
		}
		clipboard = new ArrayList<>(files);
		clipboardCut = cut;
		String what = files.size() == 1 ? files.get(0).getName() : files.size()+" items";
		// (the selection stays, as in a GUI: Ctrl-X right after Ctrl-C cuts the same files)
		setStatus((cut ? "Cut " : "Copied ")+what+": Ctrl-V "+(cut ? "moves" : "copies")+" "+(files.size() == 1 ? "it" : "them")
				+" to the directory you're in");
	}

	/** Ctrl-V: copy (or, after Ctrl-X, move) what's on the clipboard into this pane's directory */
	private void paste() {
		if( clipboard.isEmpty()) {
			setStatus("Nothing to paste: Ctrl-C copies, Ctrl-X cuts");
			return;
		}
		List<FileSource> files = clipboard;
		boolean move = clipboardCut;
		FileSource here = pane().getDirectory();
		if( allIn(files, here)) {
			setStatus((files.size() == 1 ? files.get(0).getName()+" is" : "They're")+" already here: paste in another directory");
			return;
		}
		if( move ) {
			// what was cut is moved once; then it's gone from where it was
			clipboard = new ArrayList<>();
		}
		startCopy(files, here, move);
	}

	/** true if every one of files is in dir */
	private static boolean allIn(List<FileSource> files, FileSource dir) {
		for(FileSource f : files) {
			try {
				FileSource parent = f.getParentFile();
				if( parent == null || !parent.getAbsolutePath().equals(dir.getAbsolutePath())
						|| !parent.getFileSourceFactory().isSameFileSystem(dir.getFileSourceFactory())) {
					return false;
				}
			} catch (IOException e) {
				return false;
			}
		}
		return true;
	}

	private void copy(boolean move) {
		List<FileSource> files = pane().selection();
		if( files.isEmpty()) {
			setStatus("Nothing to "+(move ? "move" : "copy"));
			return;
		}
		FileSource dest = other().getDirectory();
		String what = files.size() == 1 ? files.get(0).getName() : files.size()+" items";
		if( single ) {
			// no other pane on screen: ask where, starting from the other location
			String fs = FileOps.describe(dest.getFileSourceFactory());
			dialogs.open(new Dialog.Prompt((move ? "Move " : "Copy ")+what+" to",
					"Directory (absolute: on "+fs+"; relative: from here)", dest.getAbsolutePath(), path->{
						FileSource dir = destination(path, dest);
						if( dir != null ) {
							startCopy(files, dir, move);
						}
					}));
		} else {
			dialogs.open(new Dialog.Confirm(move ? "Move" : "Copy", (move ? "Move " : "Copy ")+what+" to ["
					+FileOps.describe(dest.getFileSourceFactory())+"] "+dest.getAbsolutePath()+" ?",
					()->startCopy(files, dest, move), ()->setStatus((move ? "Move" : "Copy")+" canceled")));
		}
	}

	/**
	 * A relative path is from this pane's directory; an absolute one is in the shell's file tree in
	 * fsh (so /mem/x is on the memory file system connected at /mem), else on suggested's file system.
	 */
	private FileSource destination(String path, FileSource suggested) {
		path = path.trim();
		if( path.isEmpty()) {
			return null;
		}
		try {
			FileSource dir = isAbsolute(path) ? connections.file(path) : pane().getDirectory().getChild(path);
			if( !dir.isDirectory()) {
				setStatus(path+" is not a directory");
				return null;
			}
			return dir;
		} catch (IOException e) {
			error("Can't use "+path, e);
			return null;
		}
	}

	private void startCopy(List<FileSource> files, FileSource dest, boolean move) {
		Progress progress = new Progress(move ? "Moving" : "Copying");
		CopyJob job = new CopyJob(files, dest, move, new CopyJob.Listener() {
			@Override
			public void file(FileSource from, FileSource to) {
				ui.execute(()->progress.line = from.getAbsolutePath());
			}

			@Override
			public void progress(long done, long total, int fDone, int fTotal) {
				ui.execute(()->progress.set(done, total, fDone, fTotal));
			}

			@Override
			public CopyJob.Conflict conflict(FileSource from, FileSource existing) {
				return askConflict(existing);
			}

			@Override
			public void finished(CopyJob.Result result) {
				ui.execute(()->{
					progress.close();
					pane().clearMarks();
					pane().refreshQuietly();
					other().refreshQuietly();
					setStatus((move ? "Moved " : "Copied ")+result);
					if( !result.getErrors().isEmpty()) {
						dialogs.open(new Dialog.TextScreen("Problems", String.join("\n", result.getErrors())));
					}
				});
			}
		});
		progress.job = job;
		dialogs.open(progress);
		worker.execute(job);
	}

	/** On the copy's thread: asks on the UI thread and waits for the answer. */
	private CopyJob.Conflict askConflict(FileSource existing) {
		CompletableFuture<CopyJob.Conflict> answer = new CompletableFuture<>();
		CopyJob.Conflict[] choices = {CopyJob.Conflict.OVERWRITE, CopyJob.Conflict.OVERWRITE_ALL,
				CopyJob.Conflict.SKIP, CopyJob.Conflict.SKIP_ALL, CopyJob.Conflict.CANCEL};
		ui.execute(()->dialogs.open(new Dialog.Choose("Already exists", existing.getAbsolutePath(),
				Arrays.asList("Overwrite", "Overwrite all", "Skip", "Skip all", "Cancel"), 64,
				idx->answer.complete(choices[idx]), ()->answer.complete(CopyJob.Conflict.CANCEL))));
		try {
			return answer.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return CopyJob.Conflict.CANCEL;
		} catch (ExecutionException e) {
			return CopyJob.Conflict.CANCEL;
		}
	}

	private void rename() {
		FileEntry e = pane().current();
		if( e == null ) {
			return;
		}
		dialogs.open(new Dialog.Prompt("Rename", e.getName(), e.getName(), name->{
			if( name.trim().isEmpty() || name.equals(e.getName())) {
				return;
			}
			try {
				FileSource target = pane().getDirectory().getChild(name.trim());
				if( target.exists()) {
					setStatus(name+" already exists");
				} else if( e.getFile().renameTo(target)) {
					pane().reloadSelecting(target.getName());
					setStatus("Renamed to "+target.getName());
				} else {
					setStatus("Can't rename "+e.getName());
				}
			} catch (IOException ex) {
				error("Can't rename "+e.getName(), ex);
			}
		}));
	}

	private void mkdir() {
		dialogs.open(new Dialog.Prompt("New directory", "Name", "", name->{
			if( name.trim().isEmpty()) {
				return;
			}
			try {
				FileSource dir = pane().getDirectory().getChild(name.trim());
				if( dir.mkdirs()) {
					pane().reloadSelecting(dir.getName());
					setStatus("Created "+dir.getName());
				} else {
					setStatus("Can't create "+name);
				}
			} catch (IOException ex) {
				error("Can't create "+name, ex);
			}
		}));
	}

	private void delete() {
		List<FileSource> files = pane().selection();
		if( files.isEmpty()) {
			return;
		}
		String what = files.size() == 1 ? files.get(0).getName() : files.size()+" items";
		dialogs.open(new Dialog.Confirm("Delete", "Delete "+what+" (and everything in directories)?", ()->{
			setStatus("Deleting "+what+" ...");
			worker.execute(()->{
				int count = 0;
				List<String> errors = new ArrayList<>();
				for(FileSource f : files) {
					try {
						count += FileOps.deleteRecursive(f, null);
					} catch (IOException e) {
						errors.add(e.getMessage());
					}
				}
				int deleted = count;
				ui.execute(()->{
					pane().clearMarks();
					pane().refreshQuietly();
					other().refreshQuietly();
					setStatus("Deleted "+deleted+" item(s)"+(errors.isEmpty() ? "" : ", "+errors.size()+" error(s)"));
					if( !errors.isEmpty()) {
						dialogs.open(new Dialog.TextScreen("Problems", String.join("\n", errors)));
					}
				});
			});
		}, ()->setStatus("Delete canceled")));
	}

	/**
	 * g: go to a path in the browser's file tree (fsh's, or its own), so the connections are
	 * there (/mem). A relative path is from the pane's folder.
	 */
	private void gotoPath() {
		Dialog.Prompt prompt = new Dialog.Prompt("Go to", "Path",
				pane().getDirectory().getAbsolutePath(), path->{
					if( path.trim().isEmpty()) {
						return;
					}
					try {
						FileSource target = resolve(path.trim());
						if( target.isDirectory()) {
							pane().go(target);
						} else if( target.exists()) {
							pane().go(target.getParentFile(), target.getName());
						} else {
							setStatus(path+" doesn't exist");
						}
					} catch (IOException e) {
						error("Can't go to "+path, e);
					}
				});
		prompt.hint = "Enter: go to it   Esc: cancel   Ctrl-U: clear   Ctrl-W: back a folder";
		dialogs.open(prompt);
	}

	/** a path typed in g, in the browser's file tree; a relative one is from the pane's folder */
	private FileSource resolve(String path) throws IOException {
		if( !isAbsolute(path)) {
			String dir = pane().getDirectory().getAbsolutePath();
			String sep = dir.indexOf('/') < 0 && dir.indexOf('\\') >= 0 ? "\\" : "/";
			path = (dir.endsWith("/") || dir.endsWith("\\") ? dir : dir+sep)+path;
		}
		return connections.file(path);
	}

	/** an absolute path: / (or \ on Windows) at the start, a drive (C:\, C:/) or a share (\\\\server) */
	public static boolean isAbsolute(String path) {
		return path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:([\\\\/].*)?");
	}

	/**
	 * A folder as the panes show it: a local folder is the same folder in the browser's file tree,
	 * so the connections are listed in it and above it (going up from a local folder with no
	 * connection under it would reach the local disk's / without them).
	 */
	private FileSource inHost(FileSource local) {
		if( !FileOps.isLocal(local)) {
			return local;
		}
		try {
			return connections.file(local.getAbsolutePath().replace('\\', '/'));
		} catch (IOException e) {
			return local;
		}
	}

	private void search() {
		FileSource root = pane().getDirectory();
		Dialog.Form form = new Dialog.Form("Search below "+Dialog.fitFront(root.getAbsolutePath(), 50));
		Field.Text name = new Field.Text("*", false, false);
		name.help = "File names to look for: * and ? as in the shell";
		Field.Text text = new Field.Text("", false, false);
		text.help = "Only files with this text in them (empty: any file)";
		Field.Check ignoreCase = new Field.Check(true, "Ignore case");
		Field.Check hidden = new Field.Check(pane().isShowHidden(), "Include hidden");
		form.add("File name", name);
		form.add("Containing", text);
		form.add("", ignoreCase);
		form.add("", hidden);
		form.footer = "Enter: search   Esc: cancel   Tab: next field   Space: tick";
		form.buttons.add(new Field.Button("Search", form::submit));
		form.buttons.add(new Field.Button("Cancel", ()->form.cancel()));
		form.submit = f->{
			dialogs.open(new SearchScreen(root, name.value(), text.value(),
					ignoreCase.isChecked(), hidden.isChecked()));
			return null;
		};
		dialogs.open(form);
	}

	// ---------------------------------------------------------------- connections

	private void connect() {
		List<String> types = Connections.types();
		List<String> labels = new ArrayList<>();
		for(String t : types) {
			labels.add(Connections.label(t));
		}
		TermPane target = pane();
		dialogs.open(new Dialog.Choose("Connect", "File system for this pane", labels, 40, idx->{
			String type = types.get(idx);
			if( type.equals(FileProxyFactory.FACTORY_ID)) {
				goLocal(target);
				return;
			}
			{
				FileSourceFactory factory;
				try {
					factory = Connections.create(type);
				} catch (IOException e) {
					error("Can't connect", e);
					return;
				}
				us.bringardner.parley.files.viewer.tui.HostConnections hc = connections;
				dialogs.open(ConnectForm.create(factory, dialogs, hc.list().keySet(), (values, startIn, connectAt)->{
					String label = labels.get(idx);
					setStatus("Connecting to "+label+" ...");
					worker.execute(()->{
						try {
							FileSource start = Connections.connect(factory, values);
							String note = null;
							if( !startIn.isEmpty()) {
								try {
									start = Connections.startIn(factory, startIn);
								} catch (IOException e) {
									note = e.getMessage();
								}
							}
							FileSource dir = connectInHost(hc, factory, start, connectAt);
							String message = note == null ? null : note+"; opened "+dir.getAbsolutePath();
							ui.execute(()->{
								setStatus(label+" connected at "+connectAt);
								target.go(dir);
								if( message != null ) {
									dialogs.open(new Dialog.Message("Start in", message));
								}
							});
						} catch (IOException | RuntimeException e) {
							String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
							ui.execute(()->{
								setStatus("Can't connect to "+label);
								dialogs.open(new Dialog.Message("Can't connect to "+label, msg));
							});
						}
					});
				}));
			}
		}, null));
	}

	/**
	 * Connect factory's file system in the host at path (it stays after the browser ends), and
	 * return start as the host has it (path/...). The factory is closed if that fails.
	 */
	private static FileSource connectInHost(us.bringardner.parley.files.viewer.tui.HostConnections hc,
			FileSourceFactory factory, FileSource start, String path) throws IOException {
		FileSource[] roots = factory.listRoots();
		IOException problem = null;
		if( roots == null || roots.length != 1 ) {
			problem = new IOException("It has "+(roots == null ? 0 : roots.length)+" top folders; it can be connected only with one");
		} else if( !hc.connect(path, roots[0])) {
			problem = new IOException("Something is already connected at "+path);
		}
		if( problem != null ) {
			try {
				factory.disConnect();
			} catch (IOException e) {
				// (what went wrong first is what matters)
			}
			throw problem;
		}
		String top = roots[0].getAbsolutePath();
		String rel = FileOps.relativePath(roots[0], start);
		boolean under = !start.getAbsolutePath().equals(top) && !rel.equals(start.getAbsolutePath());
		return hc.file(under ? path+"/"+rel.replace('\\', '/') : path);
	}

	/** D: choose one of the tree's connections (in fsh, the shell's) and disconnect it */
	/** D: the connection under the cursor, after a confirm; elsewhere, choose one from the list */
	private void disconnectKey() {
		String path = pane().connectionUnderCursor();
		FileSource top = path == null ? null : connections.list().get(path);
		if( top == null ) {
			disconnectChosen();
			return;
		}
		dialogs.open(new Dialog.Confirm("Disconnect", "Disconnect "+path+"  ("+FileOps.describe(top.getFileSourceFactory())+")?",
				()->disconnectPath(path), null));
	}

	private void disconnectPath(String path) {
		us.bringardner.parley.files.viewer.tui.HostConnections hc = connections;
		worker.execute(()->{
			try {
				hc.disconnect(path);
				ui.execute(()->{
					leave(p->p.getDirectory().getAbsolutePath().equals(path) || p.getDirectory().getAbsolutePath().startsWith(path+"/"));
					// a pane showing the folder it was in lists it no longer
					for(int i=0; i < panes.length; i++) {
						panes[i].refreshQuietly();
					}
					setStatus("Disconnected "+path);
				});
			} catch (IOException e2) {
				ui.execute(()->error("Can't disconnect "+path, e2));
			}
		});
	}

	private void disconnectChosen() {
		us.bringardner.parley.files.viewer.tui.HostConnections hc = connections;
		List<String> labels = new ArrayList<>();
		List<Runnable> actions = new ArrayList<>();
		for(java.util.Map.Entry<String, FileSource> e : hc.list().entrySet()) {
			String path = e.getKey();
			labels.add(path+"  ("+FileOps.describe(e.getValue().getFileSourceFactory())+")");
			actions.add(()->disconnectPath(path));
		}
		if( labels.isEmpty()) {
			setStatus("Nothing is connected");
			return;
		}
		dialogs.open(new Dialog.Choose("Disconnect", ownTree == null ? "The shell's connections" : "Connections",
				labels, 64, idx->dialogs.open(new Dialog.Confirm("Disconnect", "Disconnect "+labels.get(idx)+"?",
						actions.get(idx), null)), null));
	}

	/** the panes showing a file system that is going away go back to the local disk */
	private void leave(java.util.function.Predicate<TermPane> on) {
		for(TermPane p : panes) {
			if( p.getDirectory() != null && on.test(p)) {
				try {
					p.go(localStart(p));
				} catch (IOException e) {
					error("Can't open the local folder", e);
				}
			}
		}
	}

	/** x: back to the local disk, where this pane last was there (else home); connections stay (D disconnects) */
	private void disconnect() {
		TermPane target = pane();
		try {
			target.go(localStart(target));
		} catch (IOException e) {
			error("Can't open the local folder", e);
		}
	}

	/** Local in the connect list: which local folder, typed or chosen with Browse... */
	private void goLocal(TermPane target) {
		String initial;
		try {
			initial = FileOps.abbreviateHome(localStart(target).getAbsolutePath());
		} catch (IOException e) {
			error("Can't open the local folder", e);
			return;
		}
		Dialog.Form form = new Dialog.Form("Local");
		form.width = 64;
		Field.Text path = new Field.Text(initial, false, false);
		path.help = "A folder on the local disk (~ is your home folder)";
		form.add("Folder", path);
		form.footer = "Enter: open   Esc: cancel   Tab: next field";
		form.buttons.add(new Field.Button("Open", form::submit));
		Field.Button browse = new Field.Button("Browse...", ()->dialogs.open(new Dialog.Picker("Local folder",
				new File(FileOps.expandHome(path.value().trim())), false, chosen->{
					path.setValue(FileOps.abbreviateHome(chosen.getAbsolutePath()));
					form.setFocus(path);
				})));
		browse.help = "Choose the folder from a list";
		form.buttons.add(browse);
		form.buttons.add(new Field.Button("Cancel", ()->form.cancel()));
		form.submit = f->{
			try {
				FileSource dir = FileOps.fromLocalFile(new File(FileOps.expandHome(path.value().trim())));
				if( !dir.isDirectory()) {
					return path.value().trim()+" isn't a folder";
				}
				target.go(dir);
				return null;
			} catch (IOException e) {
				return "Can't open "+path.value()+": "+e.getMessage();
			}
		};
		dialogs.open(form);
	}

	private FileSource localStart(TermPane target) throws IOException {
		FileSource last = target.getLastLocal();
		return last != null && last.isDirectory() ? last : FileOps.localHome();
	}

	// ---------------------------------------------------------------- windows of its own

	/** Copy / move progress; Esc cancels the job (the window closes when the job ends). */
	private static final class Progress extends Dialog {
		private final String title;
		String line = "";
		private long bytesDone;
		private long bytesTotal;
		private int filesDone;
		private int filesTotal;
		CopyJob job;
		private boolean canceling;

		Progress(String title) {
			this.title = title;
		}

		void set(long done, long total, int fDone, int fTotal) {
			bytesDone = done;
			bytesTotal = total;
			filesDone = fDone;
			filesTotal = fTotal;
		}

		@Override
		public void render(Screen screen, Rect area) {
			Rect inner = screen.box(area.centered(60, 8), title, Theme.NORMAL);
			screen.text(inner.row(0), Dialog.fitFront(line, inner.width), Theme.NORMAL);
			int pct = bytesTotal <= 0 ? 0 : (int) Math.min(100, bytesDone*100/bytesTotal);
			int filled = inner.width*pct/100;
			screen.line(inner.row(1), "█".repeat(filled)+"░".repeat(Math.max(0, inner.width-filled)), Theme.GAUGE);
			screen.text(inner.row(2), filesDone+" / "+filesTotal+" files   "
					+FileOps.formatSize(bytesDone)+" / "+FileOps.formatSize(bytesTotal)+"   "+pct+"%", Theme.NORMAL);
			screen.text(inner.row(4), canceling ? "Canceling ..." : "Esc: cancel", Theme.HINT);
		}

		@Override
		public boolean key(Key k) {
			if( k.isCancel() || k.isChar('c')) {
				job.cancel();
				canceling = true;
			}
			return true;
		}
	}

	/** Results of a search, filled in as they're found; Enter goes to one, Esc stops and closes. */
	private final class SearchScreen extends Dialog {
		private final String title;
		private final List<FileSource> found = new ArrayList<>();
		private final List<String> labels = new ArrayList<>();
		private final ListView list = new ListView();
		private final SearchJob job;
		private int page = 10;
		private final TermPane target = pane();
		private String info = "Searching ...";

		SearchScreen(FileSource root, String glob, String text, boolean ignoreCase, boolean hidden) {
			title = "Search: "+(glob.isEmpty() ? "*" : glob)+(text.isEmpty() ? "" : " containing \""+text+"\"");
			job = new SearchJob(root, glob, text, ignoreCase, hidden, new SearchJob.Listener() {
				@Override
				public void searching(FileSource dir) {
					ui.execute(()->info = "Searching "+dir.getAbsolutePath());
				}

				@Override
				public void found(FileSource file, String line) {
					// (relative to the folder searched, which the title shows, so the matching line fits)
					String label = FileOps.relativePath(root, file).replace('\\', '/')+(line == null ? "" : "   : "+line);
					ui.execute(()->{
						found.add(file);
						labels.add(label);
					});
				}

				@Override
				public void finished(int matches, boolean canceled) {
					ui.execute(()->info = matches+" found"+(canceled ? " (stopped)" : ""));
				}
			});
			worker.execute(job);
		}

		String info() {
			return info;
		}

		int count() {
			return found.size();
		}

		@Override
		public void render(Screen screen, Rect area) {
			Rect inner = screen.box(area, title, Theme.NORMAL);
			screen.text(inner.row(0), Dialog.fitFront(info, inner.width), Theme.BOLD);
			Rect rows = inner.rows(1, Math.max(1, inner.height-2));
			page = rows.height;
			list.render(screen, rows, labels, true);
			screen.text(inner.row(inner.height-1), "Enter: go to it   Esc: stop / close", Theme.HINT);
		}

		@Override
		public boolean key(Key k) {
			if( k.isCancel()) {
				job.cancel();
				close();
			} else if( k.is(Key.Code.ENTER) && !found.isEmpty()) {
				FileSource file = found.get(list.selected());
				job.cancel();
				close();
				try {
					target.go(file.getParentFile(), file.getName());
				} catch (IOException e) {
					error("Can't go to "+file.getName(), e);
				}
			} else {
				return list.key(k, found.size(), page);
			}
			return true;
		}
	}
}
