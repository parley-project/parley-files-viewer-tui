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
		assertScreen("Moving around", "connect this pane");
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
		assertScreen("deep.txt", "the needle is here");
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
}
