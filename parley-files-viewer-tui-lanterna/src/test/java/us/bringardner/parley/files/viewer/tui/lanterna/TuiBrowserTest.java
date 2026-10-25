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
		// (the headers show the folder's own path: with \ on Windows)
		path = path.replace('/', java.io.File.separatorChar);
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

	/** Shift and an arrow marks from where it started to the cursor; copy acts on the marked ones. */
	@Test
	public void shiftArrowsMarkARangeThatCopyActsOn() throws Exception {
		key(new KeyStroke(KeyType.ArrowDown)); // folder/
		key(new KeyStroke(KeyType.ArrowDown, false, false, true)); // and readme.txt
		waitFor(()->"2 marked".equals(browser.getStatus()), "two marked");
		key(new KeyStroke(KeyType.ArrowUp, false, false, true)); // back: only folder/
		waitFor(()->"1 marked".equals(browser.getStatus()), "one marked");
		key(new KeyStroke(KeyType.ArrowDown, false, false, true));
		waitFor(()->"2 marked".equals(browser.getStatus()), "two marked again");
		// a move without Shift drops the selection
		key(new KeyStroke(KeyType.ArrowUp));
		waitFor(()->!text().contains("marked"), "the selection gone");
		key(new KeyStroke(KeyType.ArrowDown, false, false, true));
		waitFor(()->"2 marked".equals(browser.getStatus()), "a new selection");
		type('c');
		waitFor(()->text().contains("Copy 2 items"), "the confirm dialog");
		key(new KeyStroke(KeyType.Enter)); // Yes
		waitFor(()->browser.getStatus().startsWith("Copied"), "the copy");
		assertTrue(right.getChild("readme.txt").exists());
		assertTrue(right.getChild("folder").isDirectory());
	}

	/** Ctrl-C copies to the clipboard (it doesn't quit); Ctrl-V in the other pane copies there. Ctrl-X then Ctrl-V moves. */
	@Test
	public void ctrlCAndCtrlXThenCtrlV() throws Exception {
		key(new KeyStroke(KeyType.ArrowDown));
		key(new KeyStroke(KeyType.ArrowDown)); // readme.txt
		key(new KeyStroke('c', true, false));
		waitFor(()->browser.getStatus().startsWith("Copied readme.txt: Ctrl-V"), "the copy to the clipboard");
		assertTrue(thread.isAlive());
		key(new KeyStroke(KeyType.Tab));
		key(new KeyStroke('v', true, false));
		waitFor(()->browser.getStatus().startsWith("Copied 1"), "the paste");
		assertTrue(right.getChild("readme.txt").exists());
		assertTrue(left.getChild("readme.txt").exists());
		// cut the left one and paste it into folder/
		key(new KeyStroke(KeyType.Tab));
		key(new KeyStroke('x', true, false));
		waitFor(()->browser.getStatus().startsWith("Cut readme.txt"), "the cut");
		key(new KeyStroke(KeyType.ArrowUp)); // folder/
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->showsDir(text(), "/left/folder"), "entering folder");
		key(new KeyStroke('v', true, false));
		waitFor(()->browser.getStatus().startsWith("Moved 1"), "the move");
		assertTrue(left.getChild("folder").getChild("readme.txt").exists());
		assertTrue(!left.getChild("readme.txt").exists());
	}

	/** A symbolic link shows <LINK> where a folder shows <DIR>, and the status line says what it points at. */
	@Test
	public void linksAreMarked() throws Exception {
		java.nio.file.Files.createSymbolicLink(tmp.resolve("left").resolve("alias"), java.nio.file.Path.of("folder"));
		key(new KeyStroke('r', true, false)); // refresh
		waitFor(()->text().matches("(?s).*alias/ +.*<LINK>.*"), "the link marked");
		key(new KeyStroke(KeyType.ArrowDown)); // alias/ (folders first, by name)
		waitFor(()->browser.getStatus().startsWith("alias -> folder"), "what it points at");
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
		// it opens at the memory file system's root, connected in the browser's tree at Connect at
		assertEquals("/memory", browser.getPane(0).getDirectory().getAbsolutePath());
		waitFor(()->text().contains("[memory] /memory"), "the memory root in the header");
	}

	/** o, then the file system at this place in the list (Local is first). */
	private void openConnect(String type) throws InterruptedException {
		type('o');
		waitFor(()->text().contains("File system for this pane"), "the file system list");
		int idx = Connections.types().indexOf(type);
		assertTrue(idx >= 0, type);
		for(int i=0; i < idx; i++) {
			key(new KeyStroke(KeyType.ArrowDown));
		}
		key(new KeyStroke(KeyType.Enter));
	}

	private void tab(int times) {
		for(int i=0; i < times; i++) {
			key(new KeyStroke(KeyType.Tab));
		}
	}

	private void typeText(String text) {
		for(char c : text.toCharArray()) {
			type(c);
		}
	}

	@Test
	public void theConnectFormShowsOnlyWhatApplies() throws Exception {
		openConnect("sftp");
		waitFor(()->text().contains("Connect: sftp"), "the sftp form");
		String form = text();
		assertTrue(form.contains("Host *") && form.contains("User *") && form.contains("Log in with"), form);
		assertTrue(form.contains("Password"), "logging in with a password: its field\n"+form);
		assertFalse(form.contains("Private key file") || form.contains("Private key "), "not the key fields\n"+form);
		assertFalse(form.contains("Known hosts file"), "the advanced settings are behind More...\n"+form);
		assertTrue(form.contains("Start in") && form.contains("More..."), form);

		// More...: from Host, past Port, User, Log in with, Password, the cache time, Start in, Connect at, Connect and Cancel
		tab(10);
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->text().contains("Known hosts file") && text().contains("Advanced:") && text().contains("Fewer"), "the advanced settings");
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->!text().contains("Known hosts file") && text().contains("More..."), "the advanced settings hidden again");
		key(new KeyStroke(KeyType.Escape));
		waitFor(()->!text().contains("Connect: sftp"), "the form to close");

		// a key file instead of a password: its field (with Browse...), not the password's
		openConnect("sftp");
		waitFor(()->text().contains("Connect: sftp"), "the sftp form");
		tab(3);
		// a drop-down: Enter opens it, the arrow picks the next one, Enter chooses it
		key(new KeyStroke(KeyType.Enter));
		key(new KeyStroke(KeyType.ArrowDown));
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->text().contains("Private key file") && text().contains("Browse..."), "the key file field");
		assertFalse(text().contains("Password"), text());
		// the line under the form describes the field being edited
		tab(1);
		waitFor(()->text().contains("An OpenSSH or PEM private key file"), "the key file's description");
		key(new KeyStroke(KeyType.Escape));
		waitFor(()->!text().contains("Connect: sftp"), "the form to close");
	}

	@Test
	public void startInThatIsntAFolderSaysSo() throws Exception {
		openConnect("memory");
		waitFor(()->text().contains("Connect: memory"), "the connect form");
		tab(1);
		waitFor(()->text().contains("The folder to open once connected"), "Start in's description");
		typeText("/nope");
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->text().contains("/nope isn't a folder on memory; opened /memory"), "the message");
		assertEquals("/memory", browser.getPane(0).getDirectory().getAbsolutePath());
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->!text().contains("isn't a folder"), "the message to close");
	}

	/**
	 * Leaving a connection (x, or Local in the connect list) goes back to where the pane last was
	 * on the local disk, without asking: the connection stays in the browser's tree (D disconnects
	 * it, and quitting closes it).
	 */
	@Test
	public void leavingAConnectionGoesBackToTheLastLocalFolderAndKeepsIt() throws Exception {
		String leftPath = left.getAbsolutePath();
		openConnect("memory");
		waitFor(()->text().contains("Connect: memory"), "the connect form");
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->"memory".equals(browser.getPane(0).getFactory().getTypeId()) && text().contains("[memory] /memory"), "the memory pane");
		us.bringardner.parley.files.FileSourceFactory mem = browser.getPane(0).getFactory();

		type('x');
		waitFor(()->FileOps.isLocal(browser.getPane(0).getDirectory()), "the pane back on the local disk");
		assertEquals(leftPath, browser.getPane(0).getDirectory().getAbsolutePath());
		assertFalse(text().contains("Disconnect from"), text());
		assertTrue(mem.isConnected());

		// Local: which folder, starting with where this pane last was on the local disk
		openConnect("fileproxy");
		waitFor(()->text().contains("Folder on the local disk") && text().contains(leftPath), "the local folder, filled in");
		key(new KeyStroke(KeyType.Enter));
		waitFor(()->FileOps.isLocal(browser.getPane(0).getDirectory()), "still local");
		assertTrue(mem.isConnected());

		// quitting closes the browser's own connections
		type('q');
		thread.join(5000);
		assertFalse(mem.isConnected());
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
		String end = "/and-one-more/deep".replace('/', java.io.File.separatorChar);
		waitFor(()->text().contains(end), "the end of the long path");
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
		// (relative to the folder searched)
		assertTrue(text().contains("readme.txt") && !text().contains("/left/readme.txt"), text());
		key(new KeyStroke(KeyType.Escape));
		waitFor(()->!text().contains("1 found"), "results to close");
	}

	@Test
	public void aKeyFileCanBeChosenFromADropDown() throws Exception {
		java.nio.file.Path home = tmp.resolve("home");
		java.nio.file.Files.createDirectories(home.resolve(".ssh"));
		java.nio.file.Files.write(home.resolve(".ssh/id_ed25519"), "key".getBytes());
		java.nio.file.Files.write(home.resolve(".ssh/id_rsa"), "key".getBytes());
		String old = System.getProperty("user.home");
		System.setProperty("user.home", home.toString());
		try {
			openConnect("sftp");
			waitFor(()->text().contains("Connect: sftp"), "the sftp form");
			// Log in with: Private Key File
			tab(3);
			key(new KeyStroke(KeyType.Enter));
			key(new KeyStroke(KeyType.ArrowDown));
			key(new KeyStroke(KeyType.Enter));
			waitFor(()->text().contains("Private key file"), "the key file field");
			tab(1);
			waitFor(()->text().contains("Type a path, or Enter"), "the field's help");
			// Enter: the files in ~/.ssh, shown with ~; the second one is chosen
			key(new KeyStroke(KeyType.Enter));
			waitFor(()->text().contains("~/.ssh/id_ed25519") && text().contains("~/.ssh/id_rsa"), "the list of key files");
			key(new KeyStroke(KeyType.ArrowDown));
			key(new KeyStroke(KeyType.Enter));
			waitFor(()->text().contains("~/.ssh/id_rsa") && !text().contains("~/.ssh/id_ed25519"), "the chosen file in the field");
			// the factory gets the full path
			assertEquals(home.resolve(".ssh/id_rsa").toString(), FileOps.expandHome("~/.ssh/id_rsa"));
			assertEquals("~/.ssh/id_rsa", FileOps.abbreviateHome(home.resolve(".ssh/id_rsa").toString()));
			key(new KeyStroke(KeyType.Escape));
			waitFor(()->!text().contains("Connect: sftp"), "the form to close");
		} finally {
			System.setProperty("user.home", old);
		}
	}
}
