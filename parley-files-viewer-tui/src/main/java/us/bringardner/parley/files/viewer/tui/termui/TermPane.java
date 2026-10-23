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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.browse.DirectoryListing;
import us.bringardner.parley.files.browse.FileEntry;
import us.bringardner.parley.files.browse.NavigationHistory;
import us.bringardner.parley.files.viewer.tui.FileOps;
import us.bringardner.parley.term.screen.Rect;
import us.bringardner.parley.term.screen.Screen;
import us.bringardner.parley.term.screen.Style;
import us.bringardner.parley.term.screen.Theme;

/**
 * One side of the browser, as FilePane is in the Lanterna version: a directory on any file
 * system, its history, marks, sorting, and the table that shows it. Listing runs on the worker;
 * the result is applied on the UI thread.
 */
final class TermPane {

	enum Sort {
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

	private static final String PARENT = "../";
	private static final int SIZE_WIDTH = 8;
	private static final int DATE_WIDTH = 16;

	private final Executor worker;
	private final Executor ui;
	private final Consumer<String> status;
	private final NavigationHistory history = new NavigationHistory();
	private List<FileEntry> all = new ArrayList<>();
	/** the connections and symbolic links among them, by name (EntryMarks) */
	private java.util.Map<String, us.bringardner.parley.files.viewer.tui.EntryMarks.Mark> marks = java.util.Collections.emptyMap();
	/** the browser's tree's connections, by path, for the marks */
	private java.util.function.Supplier<java.util.Map<String, FileSource>> connections = java.util.Collections::emptyMap;
	private List<FileEntry> shown = new ArrayList<>();
	private final Set<String> marked = new LinkedHashSet<>();
	private int top;
	private boolean hasParent;
	private boolean showHidden;
	private Sort sort = Sort.NAME;
	private int selected;
	private int visibleRows = 20;
	private long loadSerial;
	private FileSource lastLocal;
	private java.util.function.UnaryOperator<FileSource> folders = java.util.function.UnaryOperator.identity();
	/** where a Shift-arrow selection started (a row), or -1; and what was marked before it */
	private int anchor = -1;
	private final Set<String> markedBefore = new LinkedHashSet<>();
	/** the pane with the keyboard: its header is highlighted and it reports to the status line */
	private boolean active;

	TermPane(Executor worker, Executor ui, Consumer<String> status) {
		this.worker = worker;
		this.ui = ui;
		this.status = status;
	}

	FileSource getDirectory() {
		return history.current();
	}

	FileSourceFactory getFactory() {
		FileSource d = getDirectory();
		return d == null ? null : d.getFileSourceFactory();
	}

	FileSource getLastLocal() {
		return lastLocal;
	}

	void setActive(boolean active) {
		this.active = active;
	}

	boolean isShowHidden() {
		return showHidden;
	}

	void setShowHidden(boolean on) {
		showHidden = on;
		refill(currentName());
	}

	Sort getSort() {
		return sort;
	}

	void nextSort() {
		sort = sort.next();
		refill(currentName());
	}

	// ---------------------------------------------------------------- where it is

	void go(FileSource dir) {
		go(dir, null);
	}

	/**
	 * How a folder the pane goes to is shown: as the browser's file tree has it, so its
	 * connections are listed (a local folder with none under it is a plain local folder, and so is
	 * its parent).
	 */
	void setConnections(java.util.function.Supplier<java.util.Map<String, FileSource>> connections) {
		this.connections = connections;
	}

	void setFolders(java.util.function.UnaryOperator<FileSource> folders) {
		this.folders = folders;
	}

	void go(FileSource dir, String select) {
		history.go(folders.apply(dir));
		marked.clear();
		load(select, false);
	}

	void back() {
		if( history.canGoBack()) {
			history.back();
			marked.clear();
			load(null, false);
		}
	}

	void forward() {
		if( history.canGoForward()) {
			history.forward();
			marked.clear();
			load(null, false);
		}
	}

	void up() {
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

	void refresh() {
		load(currentName(), false);
	}

	void refreshQuietly() {
		load(currentName(), true);
	}

	void reloadSelecting(String name) {
		load(name, true);
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
		if( !quiet && active ) {
			status.accept("Reading "+dir.getAbsolutePath()+" ...");
		}
		worker.execute(()->{
			List<FileEntry> entries = new ArrayList<>();
			java.util.Map<String, us.bringardner.parley.files.viewer.tui.EntryMarks.Mark> kidMarks = java.util.Collections.emptyMap();
			boolean parent = true;
			IOException failed = null;
			try {
				FileSource[] kids = dir.listFiles();
				if( kids != null ) {
					for(FileSource kid : kids) {
						entries.add(FileEntry.of(kid));
					}
					kidMarks = us.bringardner.parley.files.viewer.tui.EntryMarks.of(kids, connections.get());
				}
				parent = dir.getParentFile() != null;
			} catch (IOException | RuntimeException e) {
				failed = e instanceof IOException ? (IOException) e : new IOException(e);
			}
			boolean gotParent = parent;
			IOException error = failed;
			java.util.Map<String, us.bringardner.parley.files.viewer.tui.EntryMarks.Mark> gotMarks = kidMarks;
			ui.execute(()->{
				if( serial != loadSerial ) {
					return; // a newer load has started
				}
				all = entries;
				marks = gotMarks;
				hasParent = gotParent;
				refill(select);
				if( error != null ) {
					status.accept("Can't read "+dir.getAbsolutePath()+": "+error.getMessage());
				} else if( !quiet && active ) {
					status.accept(describeCurrent());
				}
			});
		});
	}

	private void refill(String select) {
		shown = new ArrayList<>(DirectoryListing.shown(all, showHidden));
		shown.sort(sort.order);
		selected = 0;
		if( select != null ) {
			for(int idx=0; idx < shown.size(); idx++) {
				if( shown.get(idx).getName().equals(select)) {
					selected = idx+(hasParent ? 1 : 0);
				}
			}
		}
	}

	// ---------------------------------------------------------------- the cursor and marks

	int rowCount() {
		return shown.size()+(hasParent ? 1 : 0);
	}

	boolean onParent() {
		return hasParent && selected == 0;
	}

	FileEntry current() {
		int idx = selected-(hasParent ? 1 : 0);
		return idx >= 0 && idx < shown.size() ? shown.get(idx) : null;
	}

	private String currentName() {
		FileEntry e = current();
		return e == null ? null : e.getName();
	}

	void moveTo(int row) {
		dropSelection();
		selected = Math.max(0, Math.min(rowCount()-1, row));
		status.accept(describeCurrent());
	}

	/**
	 * Shift and an arrow (or Page Up / Down, Home, End): move, marking every entry from where the
	 * Shift selection started to here, as a GUI's list does; going back unmarks what's passed.
	 * Marks made with Space stay. A move without Shift drops the selection, so the next one
	 * starts afresh.
	 */
	void extendTo(int row) {
		if( anchor < 0 ) {
			anchor = selected;
			markedBefore.clear();
			markedBefore.addAll(marked);
		}
		selected = Math.max(0, Math.min(rowCount()-1, row));
		marked.clear();
		marked.addAll(markedBefore);
		int skip = hasParent ? 1 : 0;
		for(int r = Math.min(anchor, selected); r <= Math.max(anchor, selected); r++) {
			if( r >= skip && r-skip < shown.size()) {
				marked.add(shown.get(r-skip).getName());
			}
		}
		status.accept(marked.size()+" marked");
	}

	/** A move without Shift: the Shift selection goes (what was marked with Space stays). */
	private void dropSelection() {
		if( anchor >= 0 ) {
			marked.clear();
			marked.addAll(markedBefore);
			anchor = -1;
		}
	}

	void extendBy(int rows) {
		extendTo(selected+rows);
	}

	int pageRows() {
		return visibleRows;
	}

	int rows() {
		return rowCount();
	}

	void moveBy(int rows) {
		moveTo(selected+rows);
	}

	void pageUp() {
		moveBy(-visibleRows);
	}

	void pageDown() {
		moveBy(visibleRows);
	}

	void end() {
		moveTo(rowCount()-1);
	}

	void select(String name) {
		for(int idx=0; idx < shown.size(); idx++) {
			if( shown.get(idx).getName().equals(name)) {
				selected = idx+(hasParent ? 1 : 0);
			}
		}
	}

	void toggleMark() {
		anchor = -1;
		FileEntry e = current();
		if( e != null && !marked.remove(e.getName())) {
			marked.add(e.getName());
		}
		selected = Math.min(rowCount()-1, selected+1);
		status.accept(marked.size()+" marked");
	}

	void toggleMarkAll() {
		anchor = -1;
		if( marked.size() == shown.size()) {
			marked.clear();
		} else {
			for(FileEntry e : shown) {
				marked.add(e.getName());
			}
		}
		status.accept(marked.size()+" marked");
	}

	void clearMarks() {
		anchor = -1;
		marked.clear();
	}

	/** The marked entries, or the one under the cursor when none are marked. */
	List<FileSource> selection() {
		List<FileSource> ret = new ArrayList<>();
		for(FileEntry e : shown) {
			if( marked.contains(e.getName())) {
				ret.add(e.getFile());
			}
		}
		if( ret.isEmpty() && current() != null ) {
			ret.add(current().getFile());
		}
		return ret;
	}

	/** the path of the connection under the cursor, or null (it isn't one) */
	String connectionUnderCursor() {
		FileEntry e = current();
		us.bringardner.parley.files.viewer.tui.EntryMarks.Mark m = e == null ? null : marks.get(e.getName());
		return m != null && m.connection ? e.getFile().getAbsolutePath().replace('\\', '/') : null;
	}

	String describeCurrent() {
		FileEntry e = current();
		if( e == null ) {
			return shown.size()+" item(s)"+(marked.isEmpty() ? "" : ", "+marked.size()+" marked");
		}
		us.bringardner.parley.files.viewer.tui.EntryMarks.Mark m = marks.get(e.getName());
		if( m != null ) {
			// what a connection or a link points at, as ls -l shows it
			return e.getName()+" -> "+m.target
					+"  "+FileOps.formatDate(e.getLastModified())+(marked.isEmpty() ? "" : "   ("+marked.size()+" marked)");
		}
		return e.getName()+"  "+(e.isDirectory() ? "directory" : FileOps.formatSize(e.getLength()))
				+"  "+FileOps.formatDate(e.getLastModified())+(marked.isEmpty() ? "" : "   ("+marked.size()+" marked)");
	}

	// ---------------------------------------------------------------- drawing

	/** &lt;sftp&gt; for a connection, &lt;LINK&gt; for a symbolic link, &lt;DIR&gt; for a folder, else the size */
	private String sizeColumn(FileEntry e) {
		us.bringardner.parley.files.viewer.tui.EntryMarks.Mark m = marks.get(e.getName());
		return m != null ? m.mark : e.isDirectory() ? "<DIR>" : FileOps.formatSize(e.getLength());
	}

	void render(Screen screen, Rect area, String tag) {
		renderHeader(screen, area.row(0), tag);
		renderTable(screen, area.rows(1, area.height-1));
	}

	private void renderHeader(Screen screen, Rect area, String tag) {
		FileSource dir = getDirectory();
		String lead = (active ? "> " : "  ")+(tag == null ? "" : tag+" ")
				+(dir == null ? "" : "["+FileOps.describe(dir.getFileSourceFactory())+"] ");
		String flags = (showHidden ? " +hidden" : "")+(sort == Sort.NAME ? "" : " by "+sort.name().toLowerCase());
		screen.line(area, fitHeader(lead, dir == null ? "" : dir.getAbsolutePath(), flags, area.width), active ? Theme.HEADER : Theme.BOLD);
	}

	private void renderTable(Screen screen, Rect area) {
		visibleRows = Math.max(1, area.height-1);
		int nameWidth = Math.max(10, area.width-SIZE_WIDTH-DATE_WIDTH-2);
		screen.line(area.row(0), pad("Name", nameWidth)+" "+pad("Size", SIZE_WIDTH)+" Modified", Theme.BOLD);
		List<String> lines = new ArrayList<>();
		List<Style> styles = new ArrayList<>();
		if( hasParent ) {
			lines.add(PARENT);
			styles.add(Theme.DIRECTORY);
		}
		for(FileEntry e : shown) {
			lines.add(pad(fitName(e.getName()+(e.isDirectory() ? "/" : ""), nameWidth), nameWidth)+" "
					+pad(sizeColumn(e), SIZE_WIDTH)+" "+FileOps.formatDate(e.getLastModified()));
			styles.add(marked.contains(e.getName()) ? Theme.MARKED : e.isDirectory() ? Theme.DIRECTORY : Theme.NORMAL);
		}
		if( lines.isEmpty()) {
			lines.add("(empty)");
			styles.add(Theme.NORMAL);
		}
		if( selected < top ) {
			top = selected;
		} else if( selected >= top+visibleRows ) {
			top = selected-visibleRows+1;
		}
		for(int i=0; i < visibleRows; i++) {
			int idx = top+i;
			Rect row = area.row(1+i);
			if( idx >= lines.size()) {
				screen.line(row, "", Theme.NORMAL);
				continue;
			}
			// the cursor shows only in the pane that has the keyboard
			Style style = idx == selected ? (active ? Theme.HIGHLIGHT : styles.get(idx).underline()) : styles.get(idx);
			screen.line(row, lines.get(idx), style);
		}
	}

	private static String pad(String text, int width) {
		StringBuilder b = new StringBuilder(text.length() <= width ? text : text.substring(0, Math.max(0, width)));
		while(b.length() < width) {
			b.append(' ');
		}
		return b.toString();
	}

	private static String fitName(String name, int width) {
		if( name.length() <= width ) {
			return name;
		}
		int keep = width-1;
		return name.substring(0, keep/2)+"~"+name.substring(name.length()-(keep-keep/2));
	}

	/** As FilePane.fitHeader: the path is shortened from the front (.../parent/dir). */
	static String fitHeader(String lead, String path, String flags, int width) {
		String full = lead+path+flags;
		if( full.length() <= width ) {
			return full;
		}
		int room = width-lead.length()-flags.length()-3;
		if( room < 1 ) {
			return full.substring(0, Math.max(0, width));
		}
		return lead+"..."+path.substring(path.length()-room)+flags;
	}
}
