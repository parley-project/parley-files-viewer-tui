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
package us.bringardner.parley.files.viewer.tui.lanterna;

import us.bringardner.parley.files.viewer.tui.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.ActionListBox;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.ProgressBar;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.ActionListDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.browse.FileEntry;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * A two-pane, full-screen terminal browser for any parley-files file system:
 * parley-files-viewer without a GUI. Each pane can be on a different file system (local on the
 * left, SFTP on the right, say) and copy and move work between them.
 * <p>
 * Every command has a letter key, because many terminals (macOS Terminal among them) keep some
 * function keys for themselves; the Midnight Commander function keys work too. {@code ?} lists
 * them.
 */
public class TuiBrowser {

	/** The key bar's entries, most useful first; what doesn't fit the width is left off (? lists them all). */
	static final String[] KEY_BAR = {"? help", "q quit", "Tab pane", "w layout", "v view", "e edit", "c copy", "m move",
			"d delete", "n mkdir", "r rename", "s search", "o connect", "t shell", "g goto", "h hidden"};

	/** The key bar for a screen this wide: as many entries as fit, in their usual order, help and quit last. */
	static String keyBar(int width) {
		return keyBar(width, false);
	}

	/** The key bar, with D disconnect after o connect when the cursor is on a connection. */
	static String keyBar(int width, boolean onConnection) {
		List<String> keys = new ArrayList<>(Arrays.asList(KEY_BAR));
		if( onConnection ) {
			keys.add(keys.indexOf("o connect")+1, "D disconnect");
		}
		List<String> fit = new ArrayList<>();
		int used = 0;
		for(String k : keys) {
			if( used+k.length()+2 > width ) {
				break;
			}
			fit.add(k);
			used += k.length()+2;
		}
		// help and quit are kept first so they always fit; show them at the end, where people look
		List<String> ret = new ArrayList<>(fit.subList(Math.min(2, fit.size()), fit.size()));
		ret.addAll(fit.subList(0, Math.min(2, fit.size())));
		return String.join("  ", ret);
	}

	static final String HELP = String.join("\n",
			"Moving around",
			"  Up/Down PgUp/PgDn Home/End   move the cursor (with Shift: mark from where you start to here)",
			"  Enter                        open a directory, view a file",
			"  Backspace                    parent directory",
			"  [ / ]                        back / forward in this pane's history",
			"  Tab                          switch panes (one pane: switch between the two locations)",
			"  w                            one pane / two panes",
			"  g                            go to a path",
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
			"  f                            sort by name, size, modified",
			"  Ctrl-R                       refresh",
			"  ?  F1                        this help",
			"  q  F10                       quit");

	private final Screen screen;
	private final MultiWindowTextGUI gui;
	private final ExecutorService worker = Executors.newCachedThreadPool(r->{
		Thread t = new Thread(r, "tui-worker");
		t.setDaemon(true);
		return t;
	});
	private final FilePane[] panes = new FilePane[2];
	private final Label status = new Label("");
	private final Label keys;
	private final BasicWindow main;
	private final Panel split = new Panel();
	private int active;
	private boolean single;

	/**
	 * @param screen a started screen
	 * @param left what the left pane shows first
	 * @param right what the right pane shows first
	 */
	public TuiBrowser(Screen screen, FileSource left, FileSource right) {
		this(screen, left, right, false);
	}

	/**
	 * @param single start with one pane (the full width) instead of two side by side; the other
	 *        location is still there, a Tab away
	 */
	/**
	 * The file tree the panes are in: the browser's own (pfv; its connections close when it ends),
	 * or what it runs in gives it (fsh's, with setConnections). Either way a path means one place.
	 */
	private HostConnections connections;
	private us.bringardner.parley.files.viewer.tui.TreeConnections ownTree;

	{
		try {
			ownTree = new us.bringardner.parley.files.viewer.tui.TreeConnections();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		connections = ownTree;
	}

	/** Use the tree of the program this runs in (fsh's): what's connected here stays after the browser ends. */
	public void setConnections(HostConnections connections) {
		if( connections != null ) {
			this.connections = connections;
			ownTree = null;
		}
	}

	public TuiBrowser(Screen screen, FileSource left, FileSource right, boolean single) {
		this.screen = screen;
		this.single = single;
		this.gui = new MultiWindowTextGUI(screen, TextColor.ANSI.BLACK);

		for(int idx=0; idx < panes.length; idx++) {
			panes[idx] = new FilePane(worker, gui.getGUIThread(), this::setStatus);
			panes[idx].setOnOpenFile(this::view);
			panes[idx].setFolders(this::inHost);
			panes[idx].setConnections(()->connections.list());
		}

		main = new BasicWindow() {
			@Override
			public boolean handleInput(KeyStroke key) {
				return handleKey(key) || super.handleInput(key);
			}
		};
		main.setHints(Arrays.asList(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS));

		keys = new Label(keyBar(screen.getTerminalSize().getColumns()));
		keys.setForegroundColor(TextColor.ANSI.BLACK);
		keys.setBackgroundColor(TextColor.ANSI.CYAN);

		Panel bottom = new Panel(new LinearLayout(Direction.VERTICAL));
		bottom.addComponent(status);
		bottom.addComponent(keys);

		Panel root = new Panel(new BorderLayout());
		root.addComponent(split, BorderLayout.Location.CENTER);
		root.addComponent(bottom, BorderLayout.Location.BOTTOM);
		main.setComponent(root);

		main.addWindowListener(new com.googlecode.lanterna.gui2.WindowListenerAdapter() {
			@Override
			public void onResized(Window window, TerminalSize oldSize, TerminalSize newSize) {
				fit(newSize);
			}
		});

		layoutPanes();
		panes[0].go(left);
		panes[1].go(right);
		setActive(0);
	}

	public boolean isSingle() {
		return single;
	}

	/** Switch between one pane and two. */
	public void setSingle(boolean single) {
		this.single = single;
		layoutPanes();
		setActive(active);
	}

	/** Two panes side by side, or the active one alone at full width. */
	private void layoutPanes() {
		split.removeAllComponents();
		split.setLayoutManager(new GridLayout(single ? 1 : 2).setHorizontalSpacing(1).setLeftMarginSize(0).setRightMarginSize(0));
		for(int idx=0; idx < panes.length; idx++) {
			if( !single || idx == active ) {
				split.addComponent(panes[idx], GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
			}
			panes[idx].setTag(single ? "["+(idx+1)+"/2]" : null);
		}
		fit(screen.getTerminalSize());
	}

	/** Show the browser; returns when the user quits. */
	public void run() {
		try {
			gui.addWindowAndWait(main);
		} finally {
			worker.shutdownNow();
			if( ownTree != null ) {
				// the browser's own connections end with it (fsh's stay: they're the shell's)
				ownTree.disconnectAll();
			}
		}
	}

	/** Close the browser (quit). */
	public void quit() {
		main.close();
	}

	FilePane getPane(int idx) {
		return panes[idx];
	}

	MultiWindowTextGUI getGui() {
		return gui;
	}

	String getStatus() {
		return status.getText();
	}

	private void fit(TerminalSize size) {
		if( keys != null ) {
			keys.setText(keyBar(size.getColumns(), pane().connectionUnderCursor() != null));
		}
		int paneWidth = single ? size.getColumns() : (size.getColumns()-1)/2;
		int paneHeight = size.getRows()-2;
		for(FilePane pane : panes) {
			pane.fit(paneWidth, paneHeight);
		}
	}

	private FilePane pane() {
		return panes[active];
	}

	private FilePane other() {
		return panes[1-active];
	}

	private void setActive(int idx) {
		boolean swap = single && idx != active;
		active = idx;
		if( swap ) {
			layoutPanes();
		}
		panes[idx].setActive(true);
		panes[1-idx].setActive(false);
		setStatus(panes[idx].describeCurrent());
	}

	void setStatus(String text) {
		// keep it to one line
		int nl = text == null ? -1 : text.indexOf('\n');
		String line = text == null ? "" : nl >= 0 ? text.substring(0, nl)+" ..." : text;
		// and to the screen's width: a longer label is wrapped onto lines that aren't shown
		int width = screen.getTerminalSize().getColumns();
		status.setText(line.length() <= width ? line : line.substring(0, Math.max(0, width-3))+"...");
		// (the status line follows the cursor: so does D disconnect in the key bar)
		if( keys != null ) {
			keys.setText(keyBar(width, pane().connectionUnderCursor() != null));
		}
	}

	/** The global keys; anything not handled here goes to the focused table. */
	boolean handleKey(KeyStroke key) {
		KeyType type = key.getKeyType();
		if( type == KeyType.Character && !key.isCtrlDown() && !key.isAltDown()) {
			switch (key.getCharacter()) {
				case 'q': quit(); return true;
				case 'w': setSingle(!single); return true;
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
		if( type == KeyType.Character && key.isCtrlDown() && !key.isAltDown()) {
			switch (Character.toLowerCase(key.getCharacter())) {
				case 'c': clip(false); return true;
				case 'x': clip(true); return true;
				case 'v': paste(); return true;
				default: break;
			}
		}
		if( type == KeyType.Character && key.isCtrlDown() && (key.getCharacter() == 'r' || key.getCharacter() == 'R')) {
			pane().refresh();
			return true;
		}
		switch (type) {
			case Tab:
			case ReverseTab:
				setActive(1-active);
				return true;
			case Backspace:
				pane().up();
				return true;
			case Insert:
				pane().toggleMark();
				return true;
			case Delete:
			case F8:
				delete();
				return true;
			case F1: help(); return true;
			case F3: viewSelected(); return true;
			case F4: edit(); return true;
			case F5: copy(false); return true;
			case F6: copy(true); return true;
			case F7: mkdir(); return true;
			case F10: quit(); return true;
			case ArrowUp:
			case ArrowDown:
			case PageUp:
			case PageDown:
			case Home:
			case End:
				if( key.isShiftDown()) {
					// mark from where the Shift selection started to here
					pane().extend(key);
					return true;
				}
				// let the table move, then say what's under the cursor
				pane().endExtend();
				pane().getTable().handleKeyStroke(key);
				setStatus(pane().describeCurrent());
				return true;
			default:
				return false;
		}
	}

	// ---------------------------------------------------------------- commands

	private void help() {
		showText("Keys", HELP);
	}

	private void viewSelected() {
		FileEntry e = pane().getCurrent();
		if( e == null || e.isDirectory()) {
			setStatus("Choose a file to view");
			return;
		}
		view(e.getFile());
	}

	/** Show a file in a read-only, scrolling window (text, or hex for binary files). */
	void view(FileSource file) {
		setStatus("Reading "+file.getName()+" ...");
		worker.execute(()->{
			try {
				Preview p = Preview.of(file);
				gui.getGUIThread().invokeLater(()->{
					String title = file.getName()+(p.isBinary() ? " (hex)" : "")+(p.isTruncated() ? " (first "+FileOps.formatSize(Preview.DEFAULT_LIMIT)+")" : "");
					showText(title, p.getText());
					setStatus(pane().describeCurrent());
				});
			} catch (IOException e) {
				error("Can't read "+file.getName(), e);
			}
		});
	}

	private void showText(String title, String text) {
		showText(gui, title, text);
	}

	/** A read-only, scrolling, full-screen text window; returns when it's closed (Esc or q). */
	public static void showText(WindowBasedTextGUI gui, String title, String text) {
		BasicWindow w = new BasicWindow(title) {
			@Override
			public boolean handleInput(KeyStroke key) {
				KeyType t = key.getKeyType();
				if( t == KeyType.Escape || t == KeyType.F3 || t == KeyType.F10
						|| (t == KeyType.Character && (key.getCharacter() == 'q' || key.getCharacter() == 'v'))) {
					close();
					return true;
				}
				return super.handleInput(key);
			}
		};
		w.setHints(Arrays.asList(Window.Hint.FULL_SCREEN));
		TextBox box = new TextBox(text, TextBox.Style.MULTI_LINE);
		box.setReadOnly(true);
		Panel p = new Panel(new BorderLayout());
		p.addComponent(box, BorderLayout.Location.CENTER);
		p.addComponent(new Label("Esc or q to close"), BorderLayout.Location.BOTTOM);
		w.setComponent(p);
		gui.addWindowAndWait(w);
	}

	/** The directory of the pane that was active (after run(): when the user quit). */
	public FileSource getActiveDirectory() {
		return pane().getDirectory();
	}

	private void edit() {
		FileEntry e = pane().getCurrent();
		if( e == null || e.isDirectory()) {
			setStatus("Choose a file to edit");
			return;
		}
		FileSource file = e.getFile();
		try (EditSession session = EditSession.open(file)) {
			int code = runExternal(EditSession.editorCommand(session.getLocalFile()), session.getLocalFile().getParentFile());
			if( !session.isRemote()) {
				setStatus(code == 0 ? "Edited "+file.getName() : "Editor exited with "+code);
			} else if( !session.isChanged()) {
				setStatus(file.getName()+" not changed");
			} else {
				if( session.isRemoteChanged() && !confirm("Changed elsewhere",
						file.getName()+" was changed on "+file.getFileSourceFactory().getTypeId()
						+" while you edited it.\nReplace it with your version?")) {
					setStatus("Your edit of "+file.getName()+" was not saved");
				} else {
					session.writeBack();
					setStatus("Saved "+file.getName()+" to "+FileOps.describe(file.getFileSourceFactory()));
				}
			}
		} catch (IOException ex) {
			error("Can't edit "+file.getName(), ex);
		}
		pane().refreshQuietly();
	}

	private void shell() {
		FileSource dir = pane().getDirectory();
		if( !FileOps.isLocal(dir)) {
			setStatus("A shell needs a local directory (fsh for remote file systems is planned)");
			return;
		}
		int code = runExternal(EditSession.shellCommand(), FileOps.toLocalFile(dir));
		setStatus("Shell exited"+(code == 0 ? "" : " with "+code));
		pane().refreshQuietly();
		other().refreshQuietly();
	}

	/** Give the terminal to a program (an editor, a shell) and take it back when it exits. */
	private int runExternal(List<String> command, File dir) {
		try {
			screen.stopScreen();
			try {
				Process p = new ProcessBuilder(command).directory(dir).inheritIO().start();
				return p.waitFor();
			} finally {
				screen.startScreen();
				screen.clear();
				screen.refresh(Screen.RefreshType.COMPLETE);
				main.invalidate();
			}
		} catch (IOException e) {
			error("Can't run "+command.get(0), e);
			return -1;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return -1;
		}
	}

	private void copy(boolean move) {
		List<FileSource> files = pane().getSelection();
		if( files.isEmpty()) {
			setStatus("Nothing to "+(move ? "move" : "copy"));
			return;
		}
		FileSource dest = other().getDirectory();
		String what = files.size() == 1 ? files.get(0).getName() : files.size()+" items";
		if( single ) {
			// no other pane on screen: ask where, starting from the other location
			FileSource asked = askDestination((move ? "Move " : "Copy ")+what+" to", dest);
			if( asked == null ) {
				return;
			}
			dest = asked;
		} else if( !confirm(move ? "Move" : "Copy", (move ? "Move " : "Copy ")+what+"\nto ["
				+FileOps.describe(dest.getFileSourceFactory())+"] "+dest.getAbsolutePath()+" ?")) {
			return;
		}
		startCopy(files, dest, move);
	}

	/** what Ctrl-C copied or Ctrl-X cut, for Ctrl-V */
	private List<FileSource> clipboard = new java.util.ArrayList<>();
	private boolean clipboardCut;

	private void clip(boolean cut) {
		List<FileSource> files = pane().getSelection();
		if( files.isEmpty()) {
			setStatus("Nothing to "+(cut ? "cut" : "copy"));
			return;
		}
		clipboard = new java.util.ArrayList<>(files);
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
			clipboard = new java.util.ArrayList<>();
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

	private void startCopy(List<FileSource> files, FileSource dest, boolean move) {
		ProgressWindow progress = new ProgressWindow(move ? "Moving" : "Copying");
		CopyJob job = new CopyJob(files, dest, move, new CopyJob.Listener() {
			@Override
			public void file(FileSource from, FileSource to) {
				gui.getGUIThread().invokeLater(()->progress.setLine(from.getAbsolutePath()));
			}

			@Override
			public void progress(long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
				gui.getGUIThread().invokeLater(()->progress.set(bytesDone, bytesTotal, filesDone, filesTotal));
			}

			@Override
			public CopyJob.Conflict conflict(FileSource from, FileSource existing) {
				return askConflict(existing);
			}

			@Override
			public void finished(CopyJob.Result result) {
				gui.getGUIThread().invokeLater(()->{
					progress.close();
					pane().clearMarks();
					pane().refreshQuietly();
					other().refreshQuietly();
					setStatus((move ? "Moved " : "Copied ")+result);
					if( !result.getErrors().isEmpty()) {
						showText("Problems", String.join("\n", result.getErrors()));
					}
				});
			}
		});
		progress.onCancel(job::cancel);
		gui.addWindow(progress);
		worker.execute(job);
	}

	/** Called on the copy's worker: asks on the GUI thread and waits for the answer. */
	private CopyJob.Conflict askConflict(FileSource existing) {
		CompletableFuture<CopyJob.Conflict> answer = new CompletableFuture<>();
		gui.getGUIThread().invokeLater(()->{
			CopyJob.Conflict[] choice = {CopyJob.Conflict.CANCEL};
			new ActionListDialogBuilder()
					.setTitle("Already exists")
					.setDescription(existing.getAbsolutePath())
					.setCanCancel(true)
					.addAction("Overwrite", ()->choice[0] = CopyJob.Conflict.OVERWRITE)
					.addAction("Overwrite all", ()->choice[0] = CopyJob.Conflict.OVERWRITE_ALL)
					.addAction("Skip", ()->choice[0] = CopyJob.Conflict.SKIP)
					.addAction("Skip all", ()->choice[0] = CopyJob.Conflict.SKIP_ALL)
					.addAction("Cancel", ()->choice[0] = CopyJob.Conflict.CANCEL)
					.build()
					.showDialog(gui);
			answer.complete(choice[0]);
		});
		try {
			return answer.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return CopyJob.Conflict.CANCEL;
		} catch (ExecutionException e) {
			return CopyJob.Conflict.CANCEL;
		}
	}

	/**
	 * Ask for a destination directory. A relative path is taken from this pane's directory; an
	 * absolute one is on the file system of the suggested directory (the other location), which
	 * the prompt names.
	 *
	 * @return the directory, or null if canceled or it isn't one
	 */
	private FileSource askDestination(String title, FileSource suggested) {
		String fs = FileOps.describe(suggested.getFileSourceFactory());
		String path = prompt(title, "Directory (absolute: on "+fs+"; relative: from here)", suggested.getAbsolutePath());
		if( path == null || path.trim().isEmpty()) {
			return null;
		}
		path = path.trim();
		try {
			FileSource dir = us.bringardner.parley.files.viewer.tui.termui.TermBrowser.isAbsolute(path) ? connections.file(path)
					: pane().getDirectory().getChild(path);
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

	private void rename() {
		FileEntry e = pane().getCurrent();
		if( e == null ) {
			return;
		}
		String name = prompt("Rename", e.getName(), e.getName());
		if( name == null || name.trim().isEmpty() || name.equals(e.getName())) {
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
	}

	private void mkdir() {
		String name = prompt("New directory", "Name", "");
		if( name == null || name.trim().isEmpty()) {
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
	}

	private void delete() {
		List<FileSource> files = pane().getSelection();
		if( files.isEmpty()) {
			return;
		}
		String what = files.size() == 1 ? files.get(0).getName() : files.size()+" items";
		if( !confirm("Delete", "Delete "+what+" (and everything in directories)?")) {
			return;
		}
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
			gui.getGUIThread().invokeLater(()->{
				pane().clearMarks();
				pane().refreshQuietly();
				other().refreshQuietly();
				setStatus("Deleted "+deleted+" item(s)"+(errors.isEmpty() ? "" : ", "+errors.size()+" error(s)"));
				if( !errors.isEmpty()) {
					showText("Problems", String.join("\n", errors));
				}
			});
		});
	}

	private void gotoPath() {
		String path = prompt("Go to", "Path",
				pane().getDirectory().getAbsolutePath());
		if( path == null || path.trim().isEmpty()) {
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
	}

	/** a path typed in g, in the browser's file tree; a relative one is from the pane's folder */
	private FileSource resolve(String path) throws IOException {
		if( !us.bringardner.parley.files.viewer.tui.termui.TermBrowser.isAbsolute(path)) {
			String dir = pane().getDirectory().getAbsolutePath();
			String sep = dir.indexOf('/') < 0 && dir.indexOf('\\') >= 0 ? "\\" : "/";
			path = (dir.endsWith("/") || dir.endsWith("\\") ? dir : dir+sep)+path;
		}
		return connections.file(path);
	}

	/** A folder as the panes show it: a local folder is the same folder in the browser's file tree, so the connections are listed in it and above it */
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
		BasicWindow ask = new BasicWindow("Search below "+root.getAbsolutePath());
		ask.setHints(Arrays.asList(Window.Hint.CENTERED, Window.Hint.MODAL));
		TextBox name = new TextBox(new TerminalSize(30, 1), "*");
		TextBox text = new TextBox(new TerminalSize(30, 1), "");
		CheckBox ignoreCase = new CheckBox("Ignore case").setChecked(true);
		CheckBox hidden = new CheckBox("Include hidden").setChecked(pane().isShowHidden());
		boolean[] go = {false};
		Panel form = new Panel(new GridLayout(2));
		form.addComponent(new Label("File name"));
		form.addComponent(name);
		form.addComponent(new Label("Containing"));
		form.addComponent(text);
		form.addComponent(ignoreCase);
		form.addComponent(hidden);
		form.addComponent(new Button("Search", ()->{
			go[0] = true;
			ask.close();
		}));
		form.addComponent(new Button("Cancel", ask::close));
		ask.setComponent(form);
		gui.addWindowAndWait(ask);
		if( go[0] ) {
			new SearchWindow(root, name.getText(), text.getText(), ignoreCase.isChecked(), hidden.isChecked()).start();
		}
	}

	private void connect() {
		List<String> types = Connections.types();
		String[] labels = types.stream().map(Connections::label).toArray(String[]::new);
		String label = ListSelectDialog.showDialog(gui, "Connect", "File system for this pane", labels);
		if( label == null ) {
			return;
		}
		String type = types.get(java.util.Arrays.asList(labels).indexOf(label));
		FilePane target = pane();
		if( type.equals(FileProxyFactory.FACTORY_ID)) {
			goLocal(target);
			return;
		}
		FileSourceFactory factory;
		try {
			factory = Connections.create(type);
		} catch (IOException e) {
			error("Can't connect", e);
			return;
		}
		HostConnections hc = connections;
		ConnectDialog.Answer answer = ConnectDialog.show(gui, factory, hc.list().keySet());
		if( answer == null ) {
			return;
		}
		setStatus("Connecting to "+label+" ...");
		worker.execute(()->{
			try {
				FileSource start = Connections.connect(factory, answer.values);
				String note = null;
				if( !answer.startIn.isEmpty()) {
					try {
						start = Connections.startIn(factory, answer.startIn);
					} catch (IOException e) {
						note = e.getMessage();
					}
				}
				FileSource dir = connectInHost(hc, factory, start, answer.connectAt);
				String message = note == null ? null : note+"; opened "+dir.getAbsolutePath();
				gui.getGUIThread().invokeLater(()->{
					setStatus(label+" connected at "+answer.connectAt);
					target.go(dir);
					if( message != null ) {
						// a dialog, not the status line: the pane's listing replaces that as it loads
						com.googlecode.lanterna.gui2.dialogs.MessageDialog.showMessageDialog(gui, "Start in", message,
								com.googlecode.lanterna.gui2.dialogs.MessageDialogButton.OK);
					}
				});
			} catch (IOException | RuntimeException e) {
				error("Can't connect to "+label, e);
			}
		});
	}

	/**
	 * Connect factory's file system in the host at path (it stays after the browser ends), and
	 * return start as the host has it (path/...). The factory is closed if that fails.
	 */
	private static FileSource connectInHost(HostConnections hc, FileSourceFactory factory, FileSource start, String path) throws IOException {
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
		if( confirm("Disconnect", "Disconnect "+path+"  ("+FileOps.describe(top.getFileSourceFactory())+")?")) {
			disconnectPath(path);
		}
	}

	private void disconnectPath(String path) {
		HostConnections hc = connections;
		worker.execute(()->{
			try {
				hc.disconnect(path);
				gui.getGUIThread().invokeLater(()->{
					leave(p->p.getDirectory() != null && (p.getDirectory().getAbsolutePath().equals(path)
							|| p.getDirectory().getAbsolutePath().startsWith(path+"/")));
					// a pane showing the folder it was in lists it no longer
					for(int i=0; i < panes.length; i++) {
						panes[i].refreshQuietly();
					}
					setStatus("Disconnected "+path);
				});
			} catch (IOException e2) {
				error("Can't disconnect "+path, e2);
			}
		});
	}

	private void disconnectChosen() {
		HostConnections hc = connections;
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
		String chosen = ListSelectDialog.showDialog(gui, "Disconnect",
				ownTree == null ? "The shell's connections" : "Connections", labels.toArray(new String[0]));
		if( chosen != null && confirm("Disconnect", "Disconnect "+chosen+"?")) {
			actions.get(labels.indexOf(chosen)).run();
		}
	}

	/** the panes showing a file system that is going away go back to the local disk */
	private void leave(java.util.function.Predicate<FilePane> on) {
		for(FilePane p : panes) {
			if( on.test(p)) {
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
		FilePane target = pane();
		try {
			target.go(localStart(target));
		} catch (IOException e) {
			error("Can't open the local folder", e);
		}
	}

	/** Local in the connect list: ask which local folder, starting with where this pane last was. */
	private void goLocal(FilePane target) {
		String path;
		try {
			path = askLocalFolder(localStart(target).getAbsolutePath());
		} catch (IOException e) {
			error("Can't open the local folder", e);
			return;
		}
		if( path == null || path.trim().isEmpty()) {
			return;
		}
		try {
			FileSource dir = FileOps.fromLocalFile(new java.io.File(FileOps.expandHome(path.trim())));
			if( !dir.isDirectory()) {
				setStatus(path.trim()+" isn't a folder");
				return;
			}
			target.go(dir);
		} catch (IOException e) {
			error("Can't open "+path, e);
		}
	}

	/** Where a pane goes back to on the local disk: the last local folder it showed, else home. */
	private FileSource localStart(FilePane target) throws IOException {
		FileSource last = target.getLastLocal();
		return last != null && last.isDirectory() ? last : FileOps.localHome();
	}

	/** Ask for a folder on the local disk: typed (~ is home) or chosen with Browse...; null if canceled. */
	private String askLocalFolder(String initial) {
		String[] answer = {null};
		BasicWindow w = new BasicWindow("Local");
		w.setHints(Arrays.asList(Window.Hint.CENTERED, Window.Hint.MODAL));
		TextBox box = new TextBox(new TerminalSize(Math.max(40, initial.length()+2), 1), initial) {
			@Override
			public Result handleKeyStroke(KeyStroke key) {
				if( key.getKeyType() == KeyType.Enter ) {
					answer[0] = getText();
					w.close();
					return Result.HANDLED;
				}
				if( key.getKeyType() == KeyType.Escape ) {
					w.close();
					return Result.HANDLED;
				}
				return super.handleKeyStroke(key);
			}
		};
		box.setCaretPosition(initial.length());
		Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
		buttons.addComponent(new Button("Open", ()->{
			answer[0] = box.getText();
			w.close();
		}));
		buttons.addComponent(new Button("Browse...", ()->{
			com.googlecode.lanterna.gui2.dialogs.DirectoryDialogBuilder b = new com.googlecode.lanterna.gui2.dialogs.DirectoryDialogBuilder()
					.setTitle("Local folder")
					.setActionLabel("Choose")
					.setSelectedDirectory(new java.io.File(FileOps.expandHome(box.getText().trim())));
			b.setShowHiddenDirectories(true);
			java.io.File chosen = b.build().showDialog(gui);
			if( chosen != null ) {
				box.setText(chosen.getAbsolutePath());
				box.setCaretPosition(box.getText().length());
			}
			w.setFocusedInteractable(box);
		}));
		buttons.addComponent(new Button("Cancel", w::close));
		Panel p = new Panel(new LinearLayout(Direction.VERTICAL));
		p.addComponent(new Label("Folder on the local disk"));
		p.addComponent(box);
		p.addComponent(new Label("Enter: open   Esc: cancel"));
		p.addComponent(buttons);
		w.setComponent(p);
		gui.addWindowAndWait(w);
		return answer[0];
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Ask for one line of text; Enter accepts, Esc cancels.
	 *
	 * @return the text, or null if canceled
	 */
	private String prompt(String title, String label, String initial) {
		String[] answer = {null};
		BasicWindow w = new BasicWindow(title);
		w.setHints(Arrays.asList(Window.Hint.CENTERED, Window.Hint.MODAL));
		TextBox box = new TextBox(new TerminalSize(Math.max(40, initial.length()+2), 1), initial) {
			@Override
			public Result handleKeyStroke(KeyStroke key) {
				if( key.getKeyType() == KeyType.Enter ) {
					answer[0] = getText();
					w.close();
					return Result.HANDLED;
				}
				if( key.getKeyType() == KeyType.Escape ) {
					w.close();
					return Result.HANDLED;
				}
				return super.handleKeyStroke(key);
			}
		};
		box.setCaretPosition(initial.length());
		Panel p = new Panel(new LinearLayout(Direction.VERTICAL));
		p.addComponent(new Label(label));
		p.addComponent(box);
		p.addComponent(new Label("Enter: OK   Esc: cancel"));
		w.setComponent(p);
		gui.addWindowAndWait(w);
		return answer[0];
	}

	private boolean confirm(String title, String text) {
		return MessageDialog.showMessageDialog(gui, title, text, MessageDialogButton.Yes, MessageDialogButton.No) == MessageDialogButton.Yes;
	}

	/** Report a failure in the status bar (safe from any thread). */
	private void error(String what, Exception e) {
		String msg = what+": "+(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		gui.getGUIThread().invokeLater(()->setStatus(msg));
	}

	/** Copy / move progress with a Cancel button. */
	private final class ProgressWindow extends BasicWindow {
		private final Label line = new Label("");
		private final Label counts = new Label("");
		private final ProgressBar bar = new ProgressBar(0, 100, 50);
		private Runnable cancel;

		ProgressWindow(String title) {
			super(title);
			setHints(Arrays.asList(Window.Hint.CENTERED));
			Panel p = new Panel(new LinearLayout(Direction.VERTICAL));
			p.addComponent(line);
			p.addComponent(bar);
			p.addComponent(counts);
			p.addComponent(new Button("Cancel", ()->{
				if( cancel != null ) {
					cancel.run();
				}
				counts.setText("Canceling ...");
			}));
			setComponent(p);
		}

		void onCancel(Runnable cancel) {
			this.cancel = cancel;
		}

		void setLine(String text) {
			int max = 50;
			line.setText(text.length() <= max ? text : "..."+text.substring(text.length()-max+3));
		}

		void set(long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
			bar.setValue(bytesTotal <= 0 ? 100 : (int) Math.min(100, bytesDone*100/bytesTotal));
			counts.setText(filesDone+" / "+filesTotal+" files   "+FileOps.formatSize(bytesDone)+" / "+FileOps.formatSize(bytesTotal));
		}
	}

	/** Results of a search, filled in as they're found; Enter goes to one, Esc stops and closes. */
	private final class SearchWindow extends BasicWindow {
		private final Label info = new Label("Searching ...");
		private final ActionListBox results = new ActionListBox();
		private final SearchJob job;

		SearchWindow(FileSource root, String glob, String text, boolean ignoreCase, boolean hidden) {
			super("Search: "+(glob.isEmpty() ? "*" : glob)+(text.isEmpty() ? "" : " containing \""+text+"\""));
			setHints(Arrays.asList(Window.Hint.FULL_SCREEN));
			Panel p = new Panel(new BorderLayout());
			p.addComponent(info, BorderLayout.Location.TOP);
			p.addComponent(results, BorderLayout.Location.CENTER);
			p.addComponent(new Label("Enter: go to it   Esc: stop / close"), BorderLayout.Location.BOTTOM);
			setComponent(p);
			FilePane target = pane();
			job = new SearchJob(root, glob, text, ignoreCase, hidden, new SearchJob.Listener() {
				@Override
				public void searching(FileSource dir) {
					gui.getGUIThread().invokeLater(()->info.setText("Searching "+dir.getAbsolutePath()));
				}

				@Override
				public void found(FileSource file, String line) {
					// (relative to the folder searched, which the title shows, so the matching line fits)
					String label = FileOps.relativePath(root, file).replace('\\', '/')+(line == null ? "" : "   : "+line);
					gui.getGUIThread().invokeLater(()->results.addItem(label, ()->{
						job.cancel();
						close();
						try {
							target.go(file.getParentFile(), file.getName());
						} catch (IOException e) {
							TuiBrowser.this.error("Can't go to "+file.getName(), e);
						}
					}));
				}

				@Override
				public void finished(int matches, boolean canceled) {
					gui.getGUIThread().invokeLater(()->info.setText(matches+" found"+(canceled ? " (stopped)" : "")));
				}
			});
		}

		@Override
		public boolean handleInput(KeyStroke key) {
			if( key.getKeyType() == KeyType.Escape ) {
				job.cancel();
				close();
				return true;
			}
			return super.handleInput(key);
		}

		void start() {
			gui.addWindow(this);
			worker.execute(job);
		}
	}

	// ---------------------------------------------------------------- main

	public static void main(String[] args) throws IOException {
		System.setProperty("java.awt.headless", "true");
		boolean single = false;
		List<String> dirs = new ArrayList<>();
		for(String a : args) {
			if( a.equals("--single") || a.equals("-1")) {
				single = true;
			} else if( a.equals("--help") || a.equals("-h")) {
				System.out.println("usage: parley-files-viewer-tui [--single] [left-dir] [right-dir]");
				System.out.println("  --single, -1   start with one pane (w switches)");
				return;
			} else {
				dirs.add(a);
			}
		}
		FileSource left = FileOps.fromLocalFile(new File(dirs.size() > 0 ? dirs.get(0) : ".").getCanonicalFile());
		FileSource right = dirs.size() > 1 ? FileOps.fromLocalFile(new File(dirs.get(1)).getCanonicalFile()) : FileOps.localHome();
		for(FileSource f : new FileSource[] {left, right}) {
			if( !f.isDirectory()) {
				System.err.println(f.getAbsolutePath()+" is not a directory");
				System.exit(2);
			}
		}

		boolean one = single;
		Tui.run(screen->new TuiBrowser(screen, left, right, one).run());
		System.exit(0);
	}
}
