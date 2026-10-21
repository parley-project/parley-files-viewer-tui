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
package us.bringardner.parley.files.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;

import us.bringardner.parley.files.FileSource;

/**
 * Drives the real browser on a virtual terminal: what it draws, and that the keys do what the
 * help says.
 */
public class TuiBrowserTest {

	@TempDir
	Path tmp;

	private DefaultVirtualTerminal terminal;
	private TerminalScreen screen;
	private volatile TuiBrowser browser;
	private Thread thread;
	private FileSource left;
	private FileSource right;

	@BeforeEach
	public void start() throws Exception {
		left = FileOps.fromLocalFile(tmp.resolve("left").toFile());
		right = FileOps.fromLocalFile(tmp.resolve("right").toFile());
		assertTrue(left.mkdirs());
		assertTrue(right.mkdirs());
		assertTrue(left.getChild("folder").mkdirs());
		try (OutputStream out = left.getChild("readme.txt").getOutputStream()) {
			out.write("Hello from the left pane\n".getBytes());
		}

		terminal = new DefaultVirtualTerminal(new TerminalSize(120, 30));
		screen = new TerminalScreen(terminal);
		screen.startScreen();
		// Lanterna's GUI thread is the one that creates the GUI, so build it on the thread that runs it
		java.util.concurrent.CountDownLatch built = new java.util.concurrent.CountDownLatch(1);
		thread = new Thread(()->{
			browser = new TuiBrowser(screen, left, right);
			built.countDown();
			browser.run();
		}, "browser");
		thread.setDaemon(true);
		thread.setUncaughtExceptionHandler((t, e)->e.printStackTrace());
		thread.start();
		assertTrue(built.await(10, java.util.concurrent.TimeUnit.SECONDS));
		waitFor(()->text().contains("readme.txt"), "the left listing");
	}

	@AfterEach
	public void stop() throws Exception {
		if( thread.isAlive()) {
			key(new KeyStroke('q', false, false));
			thread.join(5000);
		}
		screen.stopScreen();
	}

	private String text() {
		StringBuilder ret = new StringBuilder();
		TerminalSize size = screen.getTerminalSize();
		for(int row=0; row < size.getRows(); row++) {
			for(int col=0; col < size.getColumns(); col++) {
				ret.append(screen.getFrontCharacter(col, row).getCharacterString());
			}
			ret.append('\n');
		}
		return ret.toString();
	}

	/** A pane header ends with path (followed by a space, or at the end of its line). */
	private static boolean showsDir(String screenText, String path) {
		return screenText.contains(path+" ") || screenText.contains(path+"\n");
	}

	private void key(KeyStroke k) {
		terminal.addInput(k);
	}

	private void type(char c) {
		key(new KeyStroke(c, false, false));
	}

	private void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
		long end = System.currentTimeMillis()+10000;
		while( !condition.getAsBoolean()) {
			if( System.currentTimeMillis() > end ) {
				throw new AssertionError("Timed out waiting for "+what+"; screen:\n"+text());
			}
			Thread.sleep(50);
		}
	}

	@Test
	public void drawsBothPanesAndTheKeyBar() {
		String screenText = text();
		assertTrue(screenText.contains("folder/"), screenText);
		// (the headers show the end of each path: a long one is shortened from the front)
		assertTrue(showsDir(screenText, "/left"), screenText);
		assertTrue(showsDir(screenText, "/right"), screenText);
		assertTrue(screenText.contains("q quit"), screenText);
	}

	@Test
	public void entersDirectoriesAndGoesBackUp() throws Exception {
		// cursor starts on ".."; down once is "folder/"
		String folder = "/left/folder";
		key(new KeyStroke(KeyType.ArrowDown));
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->showsDir(text(), folder), "entering folder");
		key(new KeyStroke(KeyType.Backspace));
		waitFor(()->text().contains("readme.txt"), "back in left");
	}

	@Test
	public void copiesToTheOtherPane() throws Exception {
		key(new KeyStroke(KeyType.ArrowDown));
		key(new KeyStroke(KeyType.ArrowDown)); // readme.txt
		type('c');
		waitFor(()->text().contains("Copy readme.txt"), "the confirm dialog");
		key(new KeyStroke(KeyType.Enter)); // Yes
		waitFor(()->browser.getStatus().startsWith("Copied"), "the copy");
		assertTrue(right.getChild("readme.txt").exists());
		waitFor(()->text().split("readme.txt", -1).length == 3, "readme.txt in both panes");
	}

	@Test
	public void viewsAFile() throws Exception {
		key(new KeyStroke(KeyType.ArrowDown));
		key(new KeyStroke(KeyType.ArrowDown));
		type('v');
		waitFor(()->text().contains("Hello from the left pane"), "the viewer");
		key(new KeyStroke(KeyType.Escape));
		waitFor(()->!text().contains("Hello from the left pane"), "the viewer to close");
	}

	@Test
	public void makesDeletesAndTogglesPanes() throws Exception {
		type('n');
		waitFor(()->text().contains("New directory"), "the mkdir prompt");
		for(char c : "made".toCharArray()) {
			type(c);
		}
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->browser.getStatus().startsWith("Created made"), "mkdir");
		assertTrue(left.getChild("made").isDirectory());

		// the cursor is on the new directory; delete it
		type('d');
		waitFor(()->text().contains("Delete made"), "the delete confirm");
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->browser.getStatus().startsWith("Deleted"), "delete");
		assertFalse(left.getChild("made").exists());

		key(new KeyStroke(KeyType.Tab));
		waitFor(()->browser.getPane(1).isActive(), "the right pane to be active");
	}

	@Test
	public void connectsAPaneToAnotherFileSystem() throws Exception {
		type('o');
		waitFor(()->text().contains("File system for this pane"), "the file system list");
		// the local disk is "Local" and the ids are in lower case
		assertTrue(text().contains("│ Local "), text());
		assertFalse(text().contains("fileproxy") || text().contains("Jdbc"), text());
		int idx = Connections.types().indexOf("memory");
		assertTrue(idx > 0);
		for(int i=0; i < idx; i++) {
			key(new KeyStroke(KeyType.ArrowDown));
		}
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->text().contains("Connect: memory"), "the connect form");
		// Enter in a field connects
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->browser.getPane(0).getFactory().getTypeId().equals("memory"), "the pane to switch to memory");
		waitFor(()->!text().contains("Connect: memory"), "the form to close");
		// copy from the local right pane into memory works too
		assertEquals("memory", browser.getPane(0).getFactory().getTypeId());
		// it opens at the memory file system's root, not at a path that only exists locally
		assertEquals("/", browser.getPane(0).getDirectory().getAbsolutePath());
		waitFor(()->text().contains("[memory] / "), "the memory root in the header");
	}

	@Test
	public void switchesToOnePaneAndCopiesByPath() throws Exception {
		type('w');
		waitFor(()->browser.isSingle() && text().contains("[1/2]"), "the single-pane layout");
		assertFalse(showsDir(text(), "/right"), "only one pane is shown");

		// Tab shows the other location in the same space
		key(new KeyStroke(KeyType.Tab));
		waitFor(()->text().contains("[2/2]") && showsDir(text(), "/right"), "the second location");
		key(new KeyStroke(KeyType.Tab));
		waitFor(()->text().contains("[1/2]") && text().contains("readme.txt"), "back to the first");

		// copy asks where, suggesting the other location
		key(new KeyStroke(KeyType.ArrowDown));
		key(new KeyStroke(KeyType.ArrowDown)); // readme.txt
		type('c');
		waitFor(()->text().contains("Copy readme.txt to"), "the destination prompt");
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->browser.getStatus().startsWith("Copied"), "the copy");
		assertTrue(right.getChild("readme.txt").exists());

		type('w');
		waitFor(()->!browser.isSingle() && showsDir(text(), "/right") && !text().contains("[1/2]"), "two panes again");
	}

	@Test
	public void longPathsAreShortenedFromTheFront() throws Exception {
		FileSource deep = left;
		for(String name : "a-rather-long-directory-name/and-another-one/and-one-more/deep".split("/")) {
			deep = deep.getChild(name);
		}
		assertTrue(deep.mkdirs());
		FileSource target = deep;
		browser.getGui().getGUIThread().invokeLater(()->browser.getPane(0).go(target));
		waitFor(()->text().contains("/and-one-more/deep"), "the end of the long path");
		String first = text().split("\n")[0];
		assertTrue(first.startsWith("> [Local] ..."), first);
	}

	@Test
	public void quits() throws Exception {
		type('q');
		thread.join(5000);
		assertFalse(thread.isAlive());
	}

	@Test
	public void showsHelp() throws Exception {
		type('?');
		waitFor(()->text().contains("Marking"), "help");
		key(new KeyStroke(KeyType.Escape));
		waitFor(()->text().contains("readme.txt") && !text().contains("Marking"), "help to close");
	}

	@Test
	public void searches() throws Exception {
		type('s');
		waitFor(()->text().contains("Search below"), "the search form");
		// replace the "*" with a pattern
		key(new KeyStroke(KeyType.Backspace));
		for(char c : "*.txt".toCharArray()) {
			type(c);
		}
		// to the Search button: name, containing, two checkboxes, then Search
		for(int idx=0; idx < 4; idx++) {
			key(new KeyStroke(KeyType.Tab));
		}
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->text().contains("1 found"), "search results");
		assertTrue(text().contains("/left/readme.txt"), text());
		key(new KeyStroke(KeyType.Escape));
		waitFor(()->!text().contains("1 found"), "results to close");
	}
}
