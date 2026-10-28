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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.viewer.tui.Connections;
import us.bringardner.parley.files.viewer.tui.FileOps;
import us.bringardner.parley.term.screen.Dialog;
import us.bringardner.parley.term.screen.Field;
import us.bringardner.parley.term.tty.Key;
import us.bringardner.parley.term.screen.Screen;

/**
 * The browser on Parley's own terminal code, drawn into a screen in memory (no terminal): what's on the screen after each key,
 * with the real engine underneath (real files copied, searched, connected to).
 */
public class TermBrowserTest {

	@TempDir
	Path tmp;

	private final ExecutorService worker = Executors.newCachedThreadPool();
	/** The UI thread: results from the worker wait here until the test runs them. */
	private final BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<>();
	private final Executor ui = uiQueue::add;
	private final List<List<String>> programs = new ArrayList<>();
	private TermBrowser b;
	private FileSource src;
	private FileSource dest;

	private final TermBrowser.Host host = new TermBrowser.Host() {
		@Override
		public int runProgram(List<String> command, File dir) {
			programs.add(command);
			return 0;
		}
	};

	@BeforeEach
	public void setUp() throws IOException {
		src = local("src");
		dest = local("dest");
		src.getChild("sub").mkdirs();
		dest.mkdirs();
		write(src.getChild("a.txt"), "alpha\nsecond line\n");
		write(src.getChild("b.txt"), "bravo\n");
		write(src.getChild("sub").getChild("deep.txt"), "the needle is here\n");
		write(dest.getChild("a.txt"), "old\n");
		start(false);
	}

	private void start(boolean single) {
		b = new TermBrowser(src, dest, single, worker, ui, host);
		pumpUntil(()->screen().contains("a.txt") && (single || screen().contains("Name")));
		pumpIdle();
	}

	@AfterEach
	public void tearDown() {
		worker.shutdownNow();
	}

	private FileSource local(String rel) throws IOException {
		return FileOps.fromLocalFile(tmp.resolve(rel).toFile());
	}

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Runs what the worker handed to the UI thread until cond holds (or fails after 10 s). */
	private void pumpUntil(BooleanSupplier cond) {
		long end = System.currentTimeMillis()+10_000;
		while(!cond.getAsBoolean()) {
			assertTrue(System.currentTimeMillis() < end, "timed out; screen:\n"+screen());
			pumpOne(20);
		}
	}

	/** Runs what's waiting until nothing more comes for a moment. */
	private void pumpIdle() {
		while(pumpOne(100)) {
			// more
		}
	}

	private boolean pumpOne(long millis) {
		try {
			Runnable r = uiQueue.poll(millis, TimeUnit.MILLISECONDS);
			if( r != null ) {
				r.run();
				return true;
			}
			return false;
		} catch (InterruptedException e) {
			throw new AssertionError(e);
		}
	}

	private String activeName() {
		return b.getActiveDirectory().getName();
	}

	private void key(Key.Code code) {
		b.key(Key.of(code));
	}

	private void ch(char c) {
		b.key(Key.ch(c));
	}

	private void type(String text) {
		for(char c : text.toCharArray()) {
			ch(c);
		}
	}

	private void down(int n) {
		for(int i=0; i < n; i++) {
			key(Key.Code.DOWN);
		}
	}

	/** The screen as text, 100 x 30. */
	private String screen() {
		Screen screen = new Screen(100, 30);
		b.render(screen);
		StringBuilder ret = new StringBuilder();
		for(int y=0; y < screen.height(); y++) {
			ret.append(screen.line(y).replaceAll("\\s+$", "")).append('\n');
		}
		return ret.toString();
	}

	private List<String> lines() {
		return List.of(screen().split("\n"));
	}

	private void assertScreen(String... parts) {
		String s = screen();
		for(String p : parts) {
			assertTrue(s.contains(p), "no \""+p+"\" on the screen:\n"+s);
		}
	}

	// ---------------------------------------------------------------- panes

	@Test
	public void showsTwoPanesSideBySide() {
		List<String> l = lines();
		String header = l.get(0);
		assertTrue(header.startsWith("> [Local] ") && header.contains("src"), header);
		assertTrue(header.contains("  [Local] ") && header.trim().endsWith("dest"), header);
		// left pane: .., sub/, a.txt, b.txt; right pane: .., a.txt
		assertTrue(l.get(3).contains("sub/") && l.get(3).contains("a.txt"), l.get(3));
		assertTrue(l.get(29).startsWith("? help  q quit  Tab pane"), l.get(29));
	}

	@Test
	public void tabSwitchesPanesAndWShowsOne() {
		key(Key.Code.TAB);
		assertEquals(1, b.getActive());
		assertTrue(lines().get(0).contains("> [Local] ") && lines().get(0).indexOf("> [Local]") > 10, lines().get(0));
		ch('w');
		assertTrue(b.isSingle());
		assertTrue(lines().get(0).startsWith("> [2/2] [Local] ") && lines().get(0).endsWith("dest"), lines().get(0));
		key(Key.Code.TAB);
		assertTrue(lines().get(0).startsWith("> [1/2] [Local] ") && lines().get(0).endsWith("src"), lines().get(0));
	}

	@Test
	public void goesIntoADirectoryBackUpAndThroughHistory() {
		down(1); // sub/
		key(Key.Code.ENTER);
		pumpUntil(()->activeName().equals("sub") && screen().contains("deep.txt"));
		key(Key.Code.BACKSPACE);
		pumpUntil(()->activeName().equals("src") && b.getStatus().startsWith("sub"));
		ch('[');
		pumpUntil(()->activeName().equals("sub"));
		ch(']');
		pumpUntil(()->activeName().equals("src"));
	}

	@Test
	public void viewsAFileAndHelp() {
		down(2); // a.txt
		ch('v');
		pumpUntil(b::hasDialog);
		assertScreen("a.txt", "alpha", "second line", "Esc or q to close");
		ch('q');
		assertFalse(b.hasDialog());
		ch('?');
		assertScreen("Moving around");
		key(Key.Code.END);
		assertScreen("connect this pane");
		key(Key.Code.ESC);
		assertFalse(b.hasDialog());
	}

	@Test
	public void hiddenFilesAndSorting() throws IOException {
		write(src.getChild(".secret"), "x");
		key(Key.ctrl('r'));
		pumpIdle();
		assertFalse(screen().contains(".secret"));
		ch('h');
		assertScreen(".secret", "+hidden");
		ch('f');
		assertScreen("by size");
	}

	/**
	 * f sorts by name, size, modified, with that column marked ▲ (low to high) or ▼; F reverses
	 * the order, and the next f starts the next column in its usual direction. Folders stay first.
	 */
	@Test
	public void sortsByAColumnAndReverses() {
		start(true);
		// by name, A-Z
		assertScreen("Name \u25b2", "Size ", "Modified");
		assertBefore("sub/", "a.txt");
		assertBefore("a.txt", "b.txt");
		// Z-A
		ch('F');
		assertScreen("Name \u25bc", "by name reversed");
		assertBefore("sub/", "b.txt");
		assertBefore("b.txt", "a.txt");
		// by size, largest first: a.txt is 18 bytes, b.txt 6
		ch('f');
		assertScreen("Size \u25bc", "by size");
		assertFalse(screen().contains("reversed"), screen());
		assertFalse(screen().contains("Name \u25b2") || screen().contains("Name \u25bc"), screen());
		assertBefore("a.txt", "b.txt");
		// smallest first
		ch('F');
		assertScreen("Size \u25b2", "by size reversed");
		assertBefore("sub/", "b.txt");
		assertBefore("b.txt", "a.txt");
		// by modified, newest first, then oldest first
		ch('f');
		assertScreen("Modified \u25bc", "by modified");
		ch('F');
		assertScreen("Modified \u25b2", "by modified reversed");
		// and round to name again
		ch('f');
		assertScreen("Name \u25b2");
		assertFalse(screen().contains(" by "), screen());
		assertTrue(TermBrowser.keyBar(500).endsWith("h hidden  f sort"), TermBrowser.keyBar(500));
	}

	/** first is shown above second */
	private void assertBefore(String first, String second) {
		String s = screen();
		assertTrue(s.indexOf(first) >= 0 && s.indexOf(first) < s.indexOf(second), first+" before "+second+":\n"+s);
	}

	private void key(Key k) {
		b.key(k);
	}

	// ---------------------------------------------------------------- files

	@Test
	public void copiesToTheOtherPaneAndAsksBeforeOverwriting() throws IOException {
		down(2); // a.txt, which dest already has
		ch('c');
		assertScreen("Copy a.txt to [Local]", "y or Enter: yes");
		ch('y');
		pumpUntil(()->screen().contains("Already exists"));
		assertScreen("Overwrite all", "Skip all");
		key(Key.Code.ENTER); // Overwrite
		pumpUntil(()->b.getStatus().startsWith("Copied"));
		assertEquals("alpha\nsecond line\n", read(dest.getChild("a.txt")));
	}

	@Test
	public void escapeAtTheOverwriteQuestionCancelsTheCopy() throws IOException {
		down(2);
		ch('c');
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("Already exists"));
		key(Key.Code.ESC);
		pumpUntil(()->b.getStatus().contains("canceled"));
		assertEquals("old\n", read(dest.getChild("a.txt")));
	}

	@Test
	public void escapeCancelsAConfirm() throws IOException {
		down(3); // b.txt
		ch('c');
		key(Key.Code.ESC);
		assertFalse(b.hasDialog());
		assertEquals("Copy canceled", b.getStatus());
		assertFalse(dest.getChild("b.txt").exists());
	}

	private void shift(Key.Code code) {
		b.key(Key.shift(code));
	}

	/**
	 * Shift and an arrow marks from where it started to the cursor (going back unmarks what's
	 * passed); a move without Shift drops it, so the next one starts afresh, and what was marked
	 * with Space stays. Copy acts on it.
	 */
	@Test
	public void shiftArrowsMarkARangeThatCopyActsOn() throws IOException {
		down(1); // sub
		shift(Key.Code.DOWN);
		shift(Key.Code.DOWN);
		assertEquals("3 marked", b.getStatus());
		shift(Key.Code.UP);
		shift(Key.Code.UP);
		assertEquals("1 marked", b.getStatus()); // sub, where it started
		shift(Key.Code.UP); // onto ../, which is never marked
		assertEquals("1 marked", b.getStatus());
		pane0ClearMarks();
		down(2); // a.txt
		shift(Key.Code.DOWN);
		assertEquals("2 marked", b.getStatus()); // a.txt and b.txt
		key(Key.Code.UP); // without Shift: the selection goes
		assertFalse(screen().contains("marked"), screen());
		shift(Key.Code.UP); // a new one: sub and a.txt, not added to the old one
		assertEquals("2 marked", b.getStatus());
		// a mark made with Space stays through a selection and a move without Shift
		key(Key.Code.END);
		ch(' '); // b.txt (Space marks and moves down; it's the last, so it stays)
		key(Key.Code.HOME);
		down(1);
		shift(Key.Code.DOWN); // sub and a.txt, and b.txt marked before
		assertEquals("3 marked", b.getStatus());
		key(Key.Code.DOWN);
		assertTrue(screen().contains("1 marked"), screen());
		pane0ClearMarks();
		down(2);
		shift(Key.Code.DOWN);
		ch('c');
		assertScreen("Copy 2 items to");
		ch('y');
		pumpUntil(()->screen().contains("Already exists"));
		down(1); // Overwrite all
		key(Key.Code.ENTER);
		pumpUntil(()->b.getStatus().startsWith("Copied"));
		assertEquals("alpha\nsecond line\n", read(dest.getChild("a.txt")));
		assertEquals("bravo\n", read(dest.getChild("b.txt")));
	}

	/**
	 * Ctrl-C copies the marked files (else the one under the cursor) to the clipboard and Ctrl-V
	 * copies them into the directory the active pane is in; it can be pasted again. Ctrl-C no
	 * longer quits.
	 */
	@Test
	public void ctrlCThenCtrlVCopiesWhereYouAre() throws IOException {
		down(3); // b.txt
		b.key(Key.ctrl('c'));
		assertFalse(b.isDone());
		assertEquals("Copied b.txt: Ctrl-V copies it to the directory you're in", b.getStatus());
		assertFalse(dest.getChild("b.txt").exists());
		key(Key.Code.TAB); // the dest pane
		b.key(Key.ctrl('v'));
		pumpUntil(()->b.getStatus().startsWith("Copied 1"));
		pumpIdle();
		assertEquals("bravo\n", read(dest.getChild("b.txt")));
		assertTrue(src.getChild("b.txt").exists());
		assertScreen("b.txt");
		// again, into a folder of its own
		dest.getChild("again").mkdirs();
		b.key(Key.ctrl('r'));
		pumpIdle();
		key(Key.Code.HOME);
		down(1); // again/
		key(Key.Code.ENTER);
		pumpUntil(()->activeName().equals("again"));
		pumpIdle();
		b.key(Key.ctrl('v'));
		pumpUntil(()->b.getStatus().startsWith("Copied 1"));
		assertEquals("bravo\n", read(dest.getChild("again").getChild("b.txt")));
	}

	/** Ctrl-X then Ctrl-V moves (once: the clipboard is then empty); pasting where they already are does nothing. */
	@Test
	public void ctrlXThenCtrlVMoves() throws IOException {
		down(2);
		shift(Key.Code.DOWN); // a.txt and b.txt
		b.key(Key.ctrl('x'));
		assertEquals("Cut 2 items: Ctrl-V moves them to the directory you're in", b.getStatus());
		assertTrue(src.getChild("a.txt").exists());
		// pasting in the same directory: nothing happens, and the clipboard keeps them
		b.key(Key.ctrl('v'));
		assertEquals("They're already here: paste in another directory", b.getStatus());
		assertFalse(b.hasDialog());
		key(Key.Code.HOME);
		down(1); // sub/, then in it
		key(Key.Code.ENTER);
		pumpUntil(()->activeName().equals("sub"));
		pumpIdle();
		b.key(Key.ctrl('v'));
		pumpUntil(()->b.getStatus().startsWith("Moved 2"));
		assertFalse(src.getChild("a.txt").exists());
		assertEquals("bravo\n", read(src.getChild("sub").getChild("b.txt")));
		// a paste uses up a cut
		b.key(Key.ctrl('v'));
		assertEquals("Nothing to paste: Ctrl-C copies, Ctrl-X cuts", b.getStatus());
	}

	/** The selection stays after Ctrl-C, as in a GUI, so Ctrl-X right after it cuts the same files. */
	@Test
	public void ctrlXAfterCtrlCCutsTheSameSelection() throws IOException {
		down(3); // b.txt
		shift(Key.Code.UP); // a.txt and b.txt
		shift(Key.Code.UP); // the cursor on sub
		shift(Key.Code.UP); // and on ../ (never marked): sub, a.txt, b.txt
		assertEquals("3 marked", b.getStatus());
		b.key(Key.ctrl('c'));
		assertEquals("Copied 3 items: Ctrl-V copies them to the directory you're in", b.getStatus());
		b.key(Key.ctrl('x'));
		assertEquals("Cut 3 items: Ctrl-V moves them to the directory you're in", b.getStatus());
	}

	/** In fsh too, Ctrl-X then Ctrl-V moves. */
	@Test
	public void ctrlXThenCtrlVMovesInTheShell() throws Exception {
		startInShell(shellWithConnection());
		down(3); // b.txt
		b.key(Key.ctrl('x'));
		assertEquals("Cut b.txt: Ctrl-V moves it to the directory you're in", b.getStatus());
		key(Key.Code.TAB);
		b.key(Key.ctrl('v'));
		pumpUntil(()->b.getStatus().startsWith("Moved") || b.getStatus().startsWith("Copied"));
		pumpIdle();
		assertTrue(b.getStatus().startsWith("Moved 1"), b.getStatus());
		assertFalse(src.getChild("b.txt").exists());
		assertEquals("bravo\n", read(dest.getChild("b.txt")));
	}

	/** a (twice: all, then none) clears the marks and puts the cursor back on ../ */
	private void pane0ClearMarks() {
		ch('a');
		if( !"0 marked".equals(b.getStatus())) {
			ch('a');
		}
		assertEquals("0 marked", b.getStatus());
		key(Key.Code.HOME);
	}

	@Test
	public void movesMarkedFiles() throws IOException {
		down(2);
		ch(' ');
		ch(' '); // a.txt and b.txt
		ch('m');
		assertScreen("Move 2 items to");
		ch('y');
		pumpUntil(()->screen().contains("Already exists"));
		down(1); // Overwrite all
		key(Key.Code.ENTER);
		pumpUntil(()->b.getStatus().startsWith("Moved"));
		assertFalse(src.getChild("a.txt").exists());
		assertEquals("bravo\n", read(dest.getChild("b.txt")));
	}

	@Test
	public void onePaneAsksWhereToCopy() throws IOException {
		ch('w');
		down(3); // b.txt
		ch('c');
		assertScreen("Copy b.txt to", "Directory (absolute: on Local");
		// replace the suggestion with a folder relative to here
		key(Key.ctrl('u'));
		type("sub");
		key(Key.Code.ENTER);
		pumpUntil(()->b.getStatus().startsWith("Copied"));
		assertEquals("bravo\n", read(src.getChild("sub").getChild("b.txt")));
	}

	@Test
	public void makesRenamesAndDeletes() throws IOException {
		ch('n');
		assertScreen("New directory");
		type("made");
		key(Key.Code.ENTER);
		pumpUntil(()->b.getStatus().equals("Created made") && screen().contains("made/"));
		assertTrue(src.getChild("made").isDirectory());
		ch('r');
		assertScreen("Rename");
		key(Key.ctrl('u'));
		type("renamed");
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("renamed/"));
		assertTrue(src.getChild("renamed").isDirectory());
		ch('d');
		assertScreen("Delete renamed");
		ch('y');
		pumpUntil(()->b.getStatus().startsWith("Deleted 1"));
		assertFalse(src.getChild("renamed").exists());
	}

	@Test
	public void searchesByNameAndText() {
		ch('s');
		assertScreen("Search below", "File name", "Containing", "Ignore case");
		key(Key.Code.TAB);
		type("NEEDLE");
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("1 found"));
		assertScreen("sub/deep.txt   : the needle is here");
		key(Key.Code.ENTER);
		pumpUntil(()->activeName().equals("sub") && b.getStatus().startsWith("deep.txt"));
	}

	@Test
	public void editRunsTheEditorOnTheFile() {
		down(2);
		ch('e');
		assertEquals(1, programs.size());
		assertTrue(programs.get(0).get(programs.get(0).size()-1).endsWith("a.txt"), programs.get(0).toString());
		assertEquals("Edited a.txt", b.getStatus());
	}

	@Test
	public void quits() {
		ch('q');
		assertTrue(b.isDone());
	}

	// ---------------------------------------------------------------- connect

	/** o, then the file system called label in the list. */
	private void connectTo(String label) {
		ch('o');
		assertScreen("File system for this pane", "Local");
		int idx = 0;
		for(String t : Connections.types()) {
			if( Connections.label(t).equals(label)) {
				break;
			}
			idx++;
		}
		down(idx);
		key(Key.Code.ENTER);
	}

	@Test
	public void connectFormIsBuiltFromTheSettings() {
		connectTo("sftp");
		assertScreen("Connect: sftp", "Host *", "Port *", "User *", "Log in with", "Password", "Start in",
				"[ Connect ]", "[ Cancel ]", "[ More... / Fewer ]");
		// only the settings that apply: the password, not the key file
		assertFalse(screen().contains("Private key file"));
		// the line under the form describes the field being edited
		String s = screen();
		assertTrue(s.contains("22"), s);
		// Log in with: Private Key File shows the file field (and its Browse...) instead of the password
		down(3);
		key(Key.Code.RIGHT);
		assertScreen("Private key file *", "[ Browse... ]");
		assertFalse(screen().contains("Password  "), screen());
		// the advanced settings are behind More...
		assertFalse(screen().contains("Known hosts file"));
		for(int i=0; i < 30 && !screen().contains("More..."); i++) {
			key(Key.Code.TAB);
		}
		while(!(b.topDialog() instanceof Dialog.Form) || !(((Dialog.Form) b.topDialog()).focus() instanceof Field.Button)
				|| !((Field.Button) ((Dialog.Form) b.topDialog()).focus()).label().startsWith("More")) {
			key(Key.Code.TAB);
		}
		key(Key.Code.ENTER);
		assertScreen("Advanced:", "Known hosts file", "Check the server's key", "SSH library");
		// the values are checked before it closes: Host and User are required
		ch('\u0000'); // nothing
		Dialog.Form form = (Dialog.Form) b.topDialog();
		form.submit();
		assertTrue(b.hasDialog());
		assertTrue(form.problems.toLowerCase().contains("host"), form.problems);
		key(Key.Code.ESC);
		assertFalse(b.hasDialog());
	}

	@Test
	public void connectsThePaneToAnotherFileSystem() {
		connectTo("memory");
		assertScreen("Connect: memory", "name");
		key(Key.Code.ENTER);
		pumpUntil(()->lines().get(0).startsWith("> [memory"));
		// x goes back to the local disk, where the pane was
		ch('x');
		pumpIdle();
		if( b.hasDialog()) {
			assertScreen("Disconnect from");
			ch('y');
		}
		pumpUntil(()->lines().get(0).startsWith("> [Local]") && activeName().equals("src"));
	}

	/**
	 * A file system the caller gave the browser (a connection in fsh) is not the browser's to close:
	 * leaving it doesn't ask and doesn't disconnect it.
	 */
	@Test
	public void leavesTheCallersFileSystemsConnected() throws IOException {
		us.bringardner.parley.files.memory.MemoryFileSourceFactory mem = new us.bringardner.parley.files.memory.MemoryFileSourceFactory();
		mem.connect(new java.util.Properties());
		FileSource dir = mem.createFileSource("/shared");
		dir.mkdirs();
		write(mem.createFileSource("/shared/a.txt"), "hello");
		b = new TermBrowser(dir, dest, false, worker, ui, host);
		pumpUntil(()->screen().contains("a.txt"));
		pumpIdle();
		ch('x');
		pumpIdle();
		assertFalse(b.hasDialog(), "asked to disconnect a file system the browser didn't open:\n"+screen());
		pumpUntil(()->lines().get(0).startsWith("> [Local]"));
		pumpIdle();
		assertTrue(mem.isConnected());
		assertEquals("hello", read(mem.createFileSource("/shared/a.txt")));
	}

	/** A host's connections (as fsh's): path -> the top folder connected there. */
	private static final class FakeHost implements us.bringardner.parley.files.viewer.tui.HostConnections {
		final java.util.Map<String, FileSource> connected = new java.util.TreeMap<>();

		@Override
		public java.util.Map<String, FileSource> list() {
			return new java.util.TreeMap<>(connected);
		}

		@Override
		public boolean connect(String path, FileSource top) {
			return connected.putIfAbsent(path, top) == null;
		}

		@Override
		public void disconnect(String path) throws IOException {
			FileSource top = connected.remove(path);
			if( top == null ) {
				throw new IOException("nothing is connected at "+path);
			}
			top.getFileSourceFactory().disConnect();
		}

		@Override
		public FileSource file(String path) throws IOException {
			for(java.util.Map.Entry<String, FileSource> e : connected.entrySet()) {
				if( path.equals(e.getKey()) || path.startsWith(e.getKey()+"/")) {
					String rest = path.substring(e.getKey().length());
					return rest.isEmpty() ? e.getValue() : e.getValue().getFileSourceFactory().createFileSource(rest);
				}
			}
			throw new IOException(path+" isn't connected");
		}
	}

	/**
	 * In a host with connections (fsh), o connects the file system in the host at Connect at, so it
	 * stays when the browser ends; D lists them and disconnects one, and a pane on it goes back to
	 * the local disk.
	 */
	@Test
	public void connectsInTheHostAndDDisconnects() {
		FakeHost fake = new FakeHost();
		b = new TermBrowser(src, dest, false, worker, ui, new TermBrowser.Host() {
			@Override
			public int runProgram(List<String> command, File dir) {
				return 0;
			}

			@Override
			public us.bringardner.parley.files.viewer.tui.HostConnections connections() {
				return fake;
			}
		});
		pumpUntil(()->screen().contains("a.txt"));
		pumpIdle();
		connectTo("memory");
		assertScreen("Connect: memory", "Connect at", "/memory");
		key(Key.Code.ENTER);
		pumpUntil(()->!fake.connected.isEmpty() && lines().get(0).startsWith("> [memory"));
		pumpIdle();
		assertEquals(java.util.Set.of("/memory"), fake.connected.keySet());
		FileSourceFactory mem = fake.connected.get("/memory").getFileSourceFactory();
		// leaving it doesn't disconnect it: the host has it
		ch('x');
		pumpIdle();
		assertFalse(b.hasDialog(), screen());
		pumpUntil(()->lines().get(0).startsWith("> [Local]"));
		assertTrue(mem.isConnected());
		// a second one: the suggestion is not taken
		connectTo("memory");
		assertScreen("/memory-2");
		key(Key.Code.ESC);
		pumpIdle();
		while( b.hasDialog()) {
			key(Key.Code.ESC);
		}
		// D: the host's connections
		ch('D');
		assertScreen("Disconnect", "The shell's connections", "/memory  (");
		key(Key.Code.ENTER);
		assertScreen("Disconnect /memory (memory)?");
		ch('y');
		pumpUntil(()->fake.connected.isEmpty());
		pumpIdle();
		assertFalse(mem.isConnected());
		ch('D');
		assertEquals("Nothing is connected", b.getStatus());
	}

	@Test
	public void localAsksForAFolder() {
		connectTo("Local");
		assertScreen("Folder", "[ Open ]", "[ Browse... ]");
		key(Key.ctrl('u'));
		type(tmp.resolve("dest").toString());
		key(Key.Code.ENTER);
		pumpUntil(()->activeName().equals("dest"));
	}

	@Test
	public void browseChoosesALocalFolder() {
		connectTo("Local");
		key(Key.Code.TAB); // Open
		key(Key.Code.TAB); // Browse...
		key(Key.Code.ENTER);
		assertScreen("Local folder", "c: choose this folder");
		key(Key.Code.ESC);
		assertScreen("[ Open ]");
		key(Key.Code.ESC);
		assertFalse(b.hasDialog());
	}

	/**
	 * On its own (pfv) the browser has a file tree of its own, as fsh's: o connects at a path
	 * (Connect at), the connection is listed in the folder it's in and g reaches it, D lists it, and
	 * quitting closes it. Go to asks for a Path (one tree), never for a file system.
	 */
	@Test
	public void onItsOwnTheBrowserHasATreeLikeFshs() throws Exception {
		ch('g');
		assertScreen("Go to", "Path");
		assertFalse(screen().contains("Path on"), screen());
		assertFalse(screen().contains("fileproxy"), screen());
		key(Key.Code.ESC);
		connectTo("memory");
		assertScreen("Connect: memory", "Connect at", "/memory");
		key(Key.Code.ENTER);
		pumpUntil(()->lines().get(0).startsWith("> [memory] /memory"));
		pumpIdle();
		FileSourceFactory mem = b.getActiveDirectory().getFileSourceFactory();
		assertTrue(mem.isConnected());
		assertEquals("/memory", b.getActiveDirectory().getAbsolutePath());
		// up: it's in /, beside the local disk's folders
		key(Key.Code.LEFT);
		// (beside the local disk's folders: /tmp on Unix, Users on Windows)
		String besides = java.io.File.separatorChar == '\\' ? "Users/" : "tmp/";
		pumpUntil(()->screen().contains("memory/") && screen().contains(besides));
		pumpIdle();
		// x and back with g: still connected (only D or quitting closes it)
		ch('x');
		pumpIdle();
		ch('g');
		b.key(Key.ctrl('u'));
		type("/memory");
		key(Key.Code.ENTER);
		pumpUntil(()->b.getActiveDirectory().getAbsolutePath().equals("/memory"));
		assertTrue(mem.isConnected());
		ch('D');
		assertScreen("Disconnect", "Connections", "/memory  (");
		key(Key.Code.ESC);
		while( b.hasDialog()) {
			key(Key.Code.ESC);
		}
		ch('q');
		assertTrue(b.isDone());
		assertFalse(mem.isConnected());
	}

	/**
	 * A local symbolic link shows <LINK> and a connection its file system (<memory>) where a folder
	 * shows <DIR>; on either, the status line says what it points at, as ls -l does.
	 */
	@Test
	public void linksAndConnectionsAreMarked() throws Exception {
		java.nio.file.Files.createSymbolicLink(tmp.resolve("src").resolve("latest"), java.nio.file.Path.of("sub"));
		java.nio.file.Files.createSymbolicLink(tmp.resolve("src").resolve("note"), java.nio.file.Path.of("a.txt"));
		b.key(Key.ctrl('r'));
		pumpIdle();
		assertTrue(screen().matches("(?s).*latest/ +<LINK> .*"), screen());
		assertTrue(screen().matches("(?s).*note +<LINK> .*"), screen());
		assertTrue(screen().matches("(?s).*sub/ +<DIR> .*"), screen());
		key(Key.Code.HOME);
		down(1); // latest/ (folders first, by name)
		assertTrue(b.getStatus().startsWith("latest -> sub"), b.getStatus());

		connectTo("memory");
		key(Key.Code.ENTER);
		pumpUntil(()->lines().get(0).startsWith("> [memory] /memory"));
		pumpIdle();
		key(Key.Code.LEFT); // up to /, where it's listed
		pumpUntil(()->screen().contains("memory/"));
		pumpIdle();
		assertTrue(screen().matches("(?s).*memory/ +<memory> .*"), screen());
		assertEquals("memory", currentStatusName());
		assertTrue(b.getStatus().startsWith("memory -> memory"), b.getStatus());
	}

	/**
	 * D on a connection disconnects it, after a confirm, and a pane showing the folder it was in no
	 * longer lists it. The key bar shows D disconnect only with the cursor on a connection;
	 * elsewhere D lists the connections to choose from.
	 */
	@Test
	public void dOnAConnectionDisconnectsIt() throws Exception {
		assertFalse(TermBrowser.keyBar(500).contains("D disconnect"), TermBrowser.keyBar(500));
		assertTrue(TermBrowser.keyBar(500, true).contains("o connect  D disconnect"), TermBrowser.keyBar(500, true));
		connectTo("memory");
		key(Key.Code.ENTER);
		pumpUntil(()->lines().get(0).startsWith("> [memory] /memory"));
		pumpIdle();
		// elsewhere (inside it): D lists them
		ch('D');
		assertScreen("Disconnect", "Connections", "/memory  (");
		while( b.hasDialog()) {
			key(Key.Code.ESC);
		}
		key(Key.Code.LEFT); // up to /, the cursor on memory/
		pumpUntil(()->screen().contains("memory/"));
		pumpIdle();
		assertTrue(b.getStatus().startsWith("memory -> memory"), b.getStatus());
		ch('D');
		assertScreen("Disconnect /memory (memory)?");
		ch('y');
		pumpUntil(()->b.getStatus().startsWith("Disconnected /memory"));
		pumpUntil(()->!screen().contains("memory/"));
	}

	/** the name of the entry the status line describes */
	private String currentStatusName() {
		String st = b.getStatus();
		int sp = st.indexOf(' ');
		return sp < 0 ? st : st.substring(0, sp);
	}

	/**
	 * A browser as fsh's browse runs it: the host's connections are the shell's, a file tree with
	 * connections in it (parley-files' tree, as fsh's is).
	 */
	private void startInShell(us.bringardner.parley.files.viewer.tui.HostConnections shell) {
		b = new TermBrowser(src, dest, false, worker, ui, new TermBrowser.Host() {
			@Override
			public int runProgram(List<String> command, File dir) {
				return 0;
			}

			@Override
			public us.bringardner.parley.files.viewer.tui.HostConnections connections() {
				return shell;
			}
		});
		pumpUntil(()->screen().contains("a.txt"));
		pumpIdle();
	}

	/** A memory file system with inmem.txt in it, connected in the shell at the temp folder's conn. */
	private us.bringardner.parley.files.viewer.tui.HostConnections shellWithConnection() throws Exception {
		us.bringardner.parley.files.viewer.tui.HostConnections console = new us.bringardner.parley.files.viewer.tui.TreeConnections();
		us.bringardner.parley.files.memory.MemoryFileSourceFactory m = new us.bringardner.parley.files.memory.MemoryFileSourceFactory();
		m.connect(new java.util.Properties());
		FileSource top = m.listRoots()[0];
		write(top.getChild("inmem.txt"), "in memory\n");
		assertTrue(console.connect(tmpPath()+"/conn", top));
		return console;
	}

	private String tmpPath() {
		return tmp.toString().replace('\\', '/');
	}

	/**
	 * In fsh, g goes to a path in the shell's file tree, so a connection in that folder is listed
	 * (g used to look the path up on the local disk alone); the box names the pane's file system as
	 * its header does (Local, not fileproxy), and Ctrl-W in it goes back a folder.
	 */
	@Test
	public void gotoInTheShellListsItsConnections() throws Exception {
		startInShell(shellWithConnection());
		assertFalse(screen().contains("conn"), screen());
		ch('g');
		// (the box shows the folder's own path: with \ on Windows)
		assertScreen("Go to", "Path", "Ctrl-U: clear", "Ctrl-W: back a folder", new java.io.File(tmpPath(), "src").getPath());
		assertFalse(screen().contains("Path on"), screen());
		b.key(Key.ctrl('w'));
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("conn"));
		pumpIdle();
		// and into it: the memory file system's files
		ch('g');
		b.key(Key.ctrl('u'));
		type("conn");
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("inmem.txt"));
		// an absolute path in the shell's tree
		ch('g');
		b.key(Key.ctrl('u'));
		type(tmpPath()+"/conn/inmem.txt");
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("inmem.txt"));
		pumpIdle();
		assertEquals("conn", activeName());
	}

	/**
	 * In fsh, going up from a local folder with no connection under it (a plain local folder in fsh)
	 * reaches the shell's tree, so the connections are listed (they weren't: the parent was the
	 * local disk's).
	 */
	@Test
	public void upInTheShellListsItsConnections() throws Exception {
		startInShell(shellWithConnection());
		key(Key.Code.LEFT);
		pumpUntil(()->screen().contains("conn/"));
		pumpIdle();
		// into the connection and back up out of it
		ch('g');
		b.key(Key.ctrl('u'));
		type("conn");
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("inmem.txt"));
		pumpIdle();
		key(Key.Code.LEFT);
		pumpUntil(()->screen().contains("conn/") && screen().contains("src/"));
	}

	/** In fsh, x (back to the local disk) and Local in the connect list show the shell's tree: its connections are there. */
	@Test
	public void localInTheShellListsItsConnections() throws Exception {
		startInShell(shellWithConnection());
		ch('x');
		pumpIdle();
		key(Key.Code.LEFT);
		pumpUntil(()->screen().contains("conn"));
		pumpIdle();

		startInShell(shellWithConnection2());
		connectTo("Local");
		assertScreen("Folder");
		b.key(Key.ctrl('u'));
		type(tmpPath());
		key(Key.Code.ENTER);
		pumpUntil(()->screen().contains("conn2"));
	}

	private us.bringardner.parley.files.viewer.tui.HostConnections shellWithConnection2() throws Exception {
		us.bringardner.parley.files.viewer.tui.HostConnections console = new us.bringardner.parley.files.viewer.tui.TreeConnections();
		us.bringardner.parley.files.memory.MemoryFileSourceFactory m = new us.bringardner.parley.files.memory.MemoryFileSourceFactory();
		m.connect(new java.util.Properties());
		assertTrue(console.connect(tmpPath()+"/conn2", m.listRoots()[0]));
		return console;
	}
}
