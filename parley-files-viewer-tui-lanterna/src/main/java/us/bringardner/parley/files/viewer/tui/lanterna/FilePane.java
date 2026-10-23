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
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.BorderLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.table.DefaultTableCellRenderer;
import com.googlecode.lanterna.gui2.table.DefaultTableRenderer;
import com.googlecode.lanterna.gui2.table.Table;
import com.googlecode.lanterna.gui2.table.TableModel;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.browse.DirectoryListing;
import us.bringardner.parley.files.browse.FileEntry;
import us.bringardner.parley.files.browse.NavigationHistory;

/**
 * One side of the browser: a header with the file system and path, and a table of the
 * directory's entries (name, size, modified). Listing happens on a worker so a slow remote
 * file system doesn't freeze the screen; the table is only changed on the GUI thread.
 */
public class FilePane extends Panel {

	/** How entries are ordered; directories always come first. */
	public enum Sort {
		NAME(DirectoryListing.BY_NAME),
		SIZE(DirectoryListing.BY_SIZE.reversed()),
		MODIFIED(DirectoryListing.BY_MODIFIED.reversed());

		final Comparator<FileEntry> order;

		Sort(Comparator<FileEntry> order) {
			this.order = DirectoryListing.directoriesFirst(order);
		}

		Sort next() {
			return values()[(ordinal()+1) % values().length];
		}
	}

	private static final String PARENT = "..";
	private static final int SIZE_WIDTH = 7;
	private static final int DATE_WIDTH = 16;

	private final Label header = new Label("");
	private final Table<String> table = new Table<>("Name", "Size", "Modified");
	private final NavigationHistory history = new NavigationHistory();
	private final Executor worker;
	private final TextGUIThread guiThread;
	private final Consumer<String> status;

	private List<FileEntry> all = new ArrayList<>();
	private List<FileEntry> shown = new ArrayList<>();
	private final Set<String> marked = new LinkedHashSet<>();
	/** the connections and symbolic links shown, by name (EntryMarks) */
	private java.util.Map<String, us.bringardner.parley.files.viewer.tui.EntryMarks.Mark> marks = java.util.Collections.emptyMap();
	/** the browser's tree's connections, by path, for the marks */
	private java.util.function.Supplier<java.util.Map<String, FileSource>> connections = java.util.Collections::emptyMap;

	public void setConnections(java.util.function.Supplier<java.util.Map<String, FileSource>> connections) {
		this.connections = connections;
	}
	/** where a Shift-arrow selection started (a row), or -1; and what was marked before it */
	private int anchor = -1;
	private final Set<String> markedBefore = new LinkedHashSet<>();
	private boolean hasParent;
	private boolean showHidden;
	private boolean active;
	private Sort sort = Sort.NAME;
	private int nameWidth = 30;
	private int paneWidth = 80;
	private long loadSerial;

	public FilePane(Executor worker, TextGUIThread guiThread, Consumer<String> status) {
		super(new BorderLayout());
		this.worker = worker;
		this.guiThread = guiThread;
		this.status = status;

		DefaultTableRenderer<String> renderer = new DefaultTableRenderer<>();
		renderer.setExpandableColumns(Collections.singleton(0));
		table.setRenderer(renderer);
		table.setTableCellRenderer(new DefaultTableCellRenderer<String>() {
			@Override
			protected void applyStyle(Table<String> t, String cell, int column, int row, boolean selected, TextGUIGraphics g) {
				super.applyStyle(t, cell, column, row, selected, g);
				FileEntry e = entryAt(row);
				if( e != null && marked.contains(e.getName())) {
					g.setForegroundColor(TextColor.ANSI.YELLOW_BRIGHT);
					g.enableModifiers(SGR.BOLD);
				} else if( e != null && e.isDirectory() && !selected ) {
					g.setForegroundColor(TextColor.ANSI.BLUE_BRIGHT);
				}
			}
		});
		table.setSelectAction(this::open);

		header.addStyle(SGR.BOLD);
		addComponent(header, BorderLayout.Location.TOP);
		addComponent(table, BorderLayout.Location.CENTER);
	}

	public Table<String> getTable() {
		return table;
	}

	public FileSource getDirectory() {
		return history.current();
	}

	private FileSource lastLocal;

	/** The last folder this pane showed on the local disk, or null if it hasn't been on it. */
	public FileSource getLastLocal() {
		return lastLocal;
	}

	public FileSourceFactory getFactory() {
		FileSource dir = getDirectory();
		return dir == null ? null : dir.getFileSourceFactory();
	}

	public void setActive(boolean active) {
		this.active = active;
		updateHeader();
		if( active ) {
			table.takeFocus();
		}
	}

	private String tag;

	/** A short label shown before the path (the single-pane layout shows which of the two locations this is). */
	public void setTag(String tag) {
		this.tag = tag;
		updateHeader();
	}

	public boolean isActive() {
		return active;
	}

	public boolean isShowHidden() {
		return showHidden;
	}

	public void setShowHidden(boolean showHidden) {
		this.showHidden = showHidden;
		refill(currentName());
	}

	public Sort getSort() {
		return sort;
	}

	public void nextSort() {
		sort = sort.next();
		refill(currentName());
	}

	/** Size the table to the space the pane has: rows to show and the width of the name column. */
	public void fit(int width, int height) {
		paneWidth = width;
		nameWidth = Math.max(10, width-SIZE_WIDTH-DATE_WIDTH-4);
		table.setVisibleRows(Math.max(3, height-3));
		refill(currentName());
	}

	/** Show dir, remembering it in the history. */
	public void go(FileSource dir) {
		go(dir, null);
	}

	private java.util.function.UnaryOperator<FileSource> folders = java.util.function.UnaryOperator.identity();

	/**
	 * How a folder the pane goes to is shown: as the browser's file tree has it, so its
	 * connections are listed (a local folder with none under it is a plain local folder, and so is
	 * its parent).
	 */
	public void setFolders(java.util.function.UnaryOperator<FileSource> folders) {
		this.folders = folders;
	}

	/** Show dir and put the cursor on the entry called select (if there is one). */
	public void go(FileSource dir, String select) {
		history.go(folders.apply(dir));
		marked.clear();
		load(select);
	}

	public void back() {
		if( history.canGoBack()) {
			history.back();
			marked.clear();
			load(null);
		}
	}

	public void forward() {
		if( history.canGoForward()) {
			history.forward();
			marked.clear();
			load(null);
		}
	}

	/** Go to the parent directory, with the cursor on the one we came from. */
	public void up() {
		FileSource dir = getDirectory();
		try {
			FileSource parent = dir == null ? null : dir.getParentFile();
			if( parent != null ) {
				go(parent, dir.getName());
			}
		} catch (IOException e) {
			status.accept("Can't go up: "+e.getMessage());
		}
	}

	/** Re-read the directory, keeping the cursor on the same entry. */
	public void refresh() {
		load(currentName(), false);
	}

	/** Re-read the directory and put the cursor on name, keeping the status line. */
	public void reloadSelecting(String name) {
		load(name, true);
	}

	/** Re-read the directory without replacing the status line (after a command that set it). */
	public void refreshQuietly() {
		load(currentName(), true);
	}

	/** Enter on a row: go into a directory, or up for "..". Files are left to the caller (view). */
	public void open() {
		int row = table.getSelectedRow();
		if( hasParent && row == 0 ) {
			up();
			return;
		}
		FileEntry e = entryAt(row);
		if( e != null && e.isDirectory()) {
			go(e.getFile());
		} else if( e != null && onOpenFile != null ) {
			onOpenFile.accept(e.getFile());
		}
	}

	private Consumer<FileSource> onOpenFile;

	/** What Enter does on a file (the browser views it). */
	public void setOnOpenFile(Consumer<FileSource> onOpenFile) {
		this.onOpenFile = onOpenFile;
	}

	/** The entry under the cursor, or null on "..". */
	public FileEntry getCurrent() {
		return entryAt(table.getSelectedRow());
	}

	/** The marked entries, or the one under the cursor when none are marked. */
	public List<FileSource> getSelection() {
		List<FileSource> ret = new ArrayList<>();
		if( !marked.isEmpty()) {
			for(FileEntry e : shown) {
				if( marked.contains(e.getName())) {
					ret.add(e.getFile());
				}
			}
		} else {
			FileEntry e = getCurrent();
			if( e != null ) {
				ret.add(e.getFile());
			}
		}
		return ret;
	}

	/**
	 * Shift and an arrow (or Page Up / Down, Home, End): let the table move, marking every entry
	 * from where the Shift selection started to here, as a GUI's list does; going back unmarks
	 * what's passed. Marks made with Space stay; a move without Shift drops the selection.
	 */
	public void extend(com.googlecode.lanterna.input.KeyStroke key) {
		if( anchor < 0 ) {
			anchor = table.getSelectedRow();
			markedBefore.clear();
			markedBefore.addAll(marked);
		}
		table.handleKeyStroke(new com.googlecode.lanterna.input.KeyStroke(key.getKeyType()));
		int row = table.getSelectedRow();
		marked.clear();
		marked.addAll(markedBefore);
		for(int r = Math.min(anchor, row); r <= Math.max(anchor, row); r++) {
			FileEntry e = entryAt(r);
			if( e != null ) {
				marked.add(e.getName());
			}
		}
		table.invalidate();
		status.accept(marked.size()+" marked");
	}

	/** A move without Shift: the Shift selection goes (what was marked with Space stays). */
	public void endExtend() {
		if( anchor >= 0 ) {
			marked.clear();
			marked.addAll(markedBefore);
			anchor = -1;
			table.invalidate();
		}
	}

	/** Mark or unmark the entry under the cursor and move down. */
	public void toggleMark() {
		anchor = -1;
		FileEntry e = getCurrent();
		if( e != null ) {
			if( !marked.remove(e.getName())) {
				marked.add(e.getName());
			}
		}
		int row = table.getSelectedRow();
		if( row < table.getTableModel().getRowCount()-1 ) {
			table.setSelectedRow(row+1);
		}
		table.invalidate();
		status.accept(marked.size()+" marked");
	}

	/** Mark everything, or clear the marks if everything is marked. */
	public void toggleMarkAll() {
		anchor = -1;
		if( marked.size() == shown.size()) {
			marked.clear();
		} else {
			for(FileEntry e : shown) {
				marked.add(e.getName());
			}
		}
		table.invalidate();
		status.accept(marked.size()+" marked");
	}

	public void clearMarks() {
		anchor = -1;
		marked.clear();
		table.invalidate();
	}

	/** Put the cursor on the entry called name, if it's shown. */
	public void select(String name) {
		for(int idx=0; idx < shown.size(); idx++) {
			if( shown.get(idx).getName().equals(name)) {
				table.setSelectedRow(idx+(hasParent ? 1 : 0));
				return;
			}
		}
	}

	private void load(String select) {
		load(select, false);
	}

	private void load(String select, boolean quiet) {
		anchor = -1;
		FileSource dir = getDirectory();
		if( dir == null ) {
			return;
		}
		if( FileOps.isLocal(dir)) {
			lastLocal = dir;
		}
		long serial = ++loadSerial;
		updateHeader();
		if( !quiet ) {
			status.accept("Reading "+dir.getAbsolutePath()+" ...");
		}
		worker.execute(()->{
			List<FileEntry> entries;
			java.util.Map<String, us.bringardner.parley.files.viewer.tui.EntryMarks.Mark> kidMarks = java.util.Collections.emptyMap();
			boolean parent;
			IOException error = null;
			try {
				FileSource[] kids = dir.listFiles();
				entries = list(kids);
				kidMarks = us.bringardner.parley.files.viewer.tui.EntryMarks.of(kids, connections.get());
				parent = dir.getParentFile() != null;
			} catch (IOException | RuntimeException e) {
				entries = new ArrayList<>();
				parent = true;
				error = e instanceof IOException ? (IOException) e : new IOException(e);
			}
			List<FileEntry> got = entries;
			java.util.Map<String, us.bringardner.parley.files.viewer.tui.EntryMarks.Mark> gotMarks = kidMarks;
			boolean gotParent = parent;
			IOException failed = error;
			guiThread.invokeLater(()->{
				if( serial != loadSerial ) {
					return; // a newer load has started
				}
				all = got;
				marks = gotMarks;
				hasParent = gotParent;
				refill(select);
				if( failed != null ) {
					status.accept("Can't read "+dir.getAbsolutePath()+": "+failed.getMessage());
				} else if( !quiet && active ) {
					status.accept(describeCurrent());
				}
			});
		});
	}

	/**
	 * The directory's entries. Uses the plain listFiles() rather than DirectoryListing.list (which
	 * calls listFiles(progress)): some FileSources implement only the plain one, and a file tree
	 * (fsh's, the browser's own) lists its connections only through it.
	 */
	static List<FileEntry> list(FileSource dir) throws IOException {
		return list(dir.listFiles());
	}

	static List<FileEntry> list(FileSource[] kids) {
		List<FileEntry> ret = new ArrayList<>(kids == null ? 0 : kids.length);
		if( kids != null ) {
			for(FileSource kid : kids) {
				ret.add(FileEntry.of(kid));
			}
		}
		ret.sort(DirectoryListing.BY_NAME);
		return ret;
	}

	/** &lt;sftp&gt; for a connection, &lt;LINK&gt; for a symbolic link, &lt;DIR&gt; for a folder, else the size */
	private String sizeColumn(FileEntry e) {
		us.bringardner.parley.files.viewer.tui.EntryMarks.Mark m = marks.get(e.getName());
		return m != null ? m.mark : e.isDirectory() ? "<DIR>" : FileOps.formatSize(e.getLength());
	}

	private void refill(String select) {
		shown = new ArrayList<>(DirectoryListing.shown(all, showHidden));
		shown.sort(sort.order);
		TableModel<String> model = table.getTableModel();
		model.clear();
		if( hasParent ) {
			model.addRow(PARENT+"/", "", "");
		}
		for(FileEntry e : shown) {
			String name = e.getName()+(e.isDirectory() ? "/" : "");
			model.addRow(fitName(name),
					sizeColumn(e),
					FileOps.formatDate(e.getLastModified()));
		}
		if( model.getRowCount() == 0 ) {
			model.addRow("(empty)", "", "");
		}
		int row = 0;
		if( select != null ) {
			for(int idx=0; idx < shown.size(); idx++) {
				if( shown.get(idx).getName().equals(select)) {
					row = idx+(hasParent ? 1 : 0);
					break;
				}
			}
		}
		table.setSelectedRow(Math.min(row, model.getRowCount()-1));
		updateHeader();
	}

	private String fitName(String name) {
		if( name.length() <= nameWidth ) {
			return name;
		}
		int keep = nameWidth-1;
		return name.substring(0, keep/2)+"~"+name.substring(name.length()-(keep-keep/2));
	}

	private void updateHeader() {
		FileSource dir = getDirectory();
		String lead = (active ? "> " : "  ")+(tag == null ? "" : tag+" ")
				+(dir == null ? "" : "["+FileOps.describe(dir.getFileSourceFactory())+"] ");
		String flags = (showHidden ? " +hidden" : "")+(sort == Sort.NAME ? "" : " by "+sort.name().toLowerCase());
		header.setText(fitHeader(lead, dir == null ? "" : dir.getAbsolutePath(), flags, paneWidth));
		if( active ) {
			header.setForegroundColor(TextColor.ANSI.BLACK);
			header.setBackgroundColor(TextColor.ANSI.CYAN);
		} else {
			header.setForegroundColor(null);
			header.setBackgroundColor(null);
		}
	}

	/**
	 * lead + path + flags in at most width columns, on one line. A label longer than its space is
	 * word-wrapped by Lanterna and only its first line shows, which lost the whole path in a deep
	 * directory; so the path is shortened from the front instead (.../parent/dir), keeping the
	 * part that says where you are.
	 */
	static String fitHeader(String lead, String path, String flags, int width) {
		String full = lead+path+flags;
		if( full.length() <= width ) {
			return full;
		}
		int room = width-lead.length()-flags.length()-3;
		if( room < 1 ) {
			// not even room for a bit of the path: whatever fits of the start
			return full.substring(0, Math.max(0, width));
		}
		return lead+"..."+path.substring(path.length()-room)+flags;
	}

	private FileEntry entryAt(int row) {
		int idx = row-(hasParent ? 1 : 0);
		return idx >= 0 && idx < shown.size() ? shown.get(idx) : null;
	}

	private String currentName() {
		FileEntry e = getCurrent();
		return e == null ? null : e.getName();
	}

	/** One line about the entry under the cursor, for the status bar. */
	public String describeCurrent() {
		FileEntry e = getCurrent();
		if( e == null ) {
			return shown.size()+" item(s)"+(marked.isEmpty() ? "" : ", "+marked.size()+" marked");
		}
		us.bringardner.parley.files.viewer.tui.EntryMarks.Mark m = marks.get(e.getName());
		if( m != null ) {
			// what a connection or a link points at, as ls -l shows it
			return e.getName()+" -> "+m.target+"  "+FileOps.formatDate(e.getLastModified())
					+(marked.isEmpty() ? "" : "   ("+marked.size()+" marked)");
		}
		String what = e.isDirectory() ? "directory" : FileOps.formatSize(e.getLength());
		return e.getName()+"  "+what+"  "+FileOps.formatDate(e.getLastModified())
				+(marked.isEmpty() ? "" : "   ("+marked.size()+" marked)");
	}
}
