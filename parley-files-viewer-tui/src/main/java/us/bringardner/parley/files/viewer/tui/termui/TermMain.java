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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.viewer.tui.FileOps;
import us.bringardner.parley.term.tty.NativeTerminal;
import us.bringardner.parley.term.screen.Dialog;
import us.bringardner.parley.term.screen.Screen;
import us.bringardner.parley.term.tty.Key;
import us.bringardner.parley.term.tty.Terminal;
import us.bringardner.parley.term.screen.Tui;

/** Starts the browser on Parley's own terminal code. Usage: [--single] [left-dir] [right-dir]. */
public final class TermMain {

	private TermMain() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		boolean single = false;
		List<String> dirs = new ArrayList<>();
		for(String a : args) {
			if( a.equals("--single") || a.equals("-1")) {
				single = true;
			} else if( a.equals("--help") || a.equals("-h")) {
				System.out.println("usage: pfv-native [--single] [left-dir] [right-dir]");
				return;
			} else {
				dirs.add(a);
			}
		}
		FileSource left = FileOps.fromLocalFile(new File(FileOps.expandHome(dirs.size() > 0 ? dirs.get(0) : ".")).getCanonicalFile());
		FileSource right = dirs.size() > 1 ? FileOps.fromLocalFile(new File(FileOps.expandHome(dirs.get(1))).getCanonicalFile()) : FileOps.localHome();
		for(FileSource f : new FileSource[] {left, right}) {
			if( !f.isDirectory()) {
				System.err.println(f.getAbsolutePath()+" is not a directory");
				System.exit(2);
			}
		}
		if( !NativeTerminal.isAvailable()) {
			System.err.println("Needs a terminal (and JNA, for raw mode).");
			System.exit(1);
		}
		run(left, right, single, new NativeTerminal(true));
		System.exit(0);
	}

	/** Shows text full-screen on terminal (a pager: Esc or q closes it), as the browser's F3 does. */
	public static void showText(String title, String text, Terminal terminal) throws IOException {
		Dialog.TextScreen view = new Dialog.TextScreen(title, text);
		new Tui(terminal).run(new Tui.App() {
			@Override
			public void render(Screen screen) {
				screen.clear();
				view.render(screen, screen.area());
			}

			@Override
			public boolean key(Key key) {
				return view.key(key);
			}

			@Override
			public boolean isDone() {
				return view.isClosed();
			}
		});
	}

	/** Runs the browser on terminal until it's quit; returns the directory of the pane that had the keyboard. */
	public static FileSource run(FileSource left, FileSource right, boolean single, Terminal terminal) throws Exception {
		return run(left, right, single, terminal, null);
	}

	/**
	 * Runs the browser on terminal until it's quit, in a host with connections (fsh's: what is
	 * connected in the browser is connected there), or null for a tree of the browser's own.
	 */
	public static FileSource run(FileSource left, FileSource right, boolean single, Terminal terminal,
			us.bringardner.parley.files.viewer.tui.HostConnections connections) throws Exception {
		ExecutorService worker = Executors.newCachedThreadPool(r->{
			Thread t = new Thread(r, "term-browser-worker");
			t.setDaemon(true);
			return t;
		});
		Tui tui = new Tui(terminal);
		AtomicReference<TermBrowser> browser = new AtomicReference<>();
		TermBrowser.Host host = new TermBrowser.Host() {
			@Override
			public int runProgram(List<String> command, File dir) throws IOException {
				return tui.lend(new ProcessBuilder(command).directory(dir));
			}

			@Override
			public us.bringardner.parley.files.viewer.tui.HostConnections connections() {
				return connections;
			}
		};
		try {
			browser.set(new TermBrowser(left, right, single, worker, tui.ui, host));
			tui.run(browser.get());
		} finally {
			worker.shutdownNow();
		}
		return browser.get().getActiveDirectory();
	}
}
