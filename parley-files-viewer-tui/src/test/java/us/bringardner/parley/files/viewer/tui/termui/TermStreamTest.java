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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.viewer.tui.FileOps;
import us.bringardner.parley.term.tty.StreamTerminal;

/**
 * The whole thing over plain streams, as an SSH channel would hand it: the event loop, the key
 * decoder and the screen, with no terminal at all.
 */
public class TermStreamTest {

	@TempDir
	Path tmp;

	/** Output written so far (the run writes it on its own thread). */
	private static String text(ByteArrayOutputStream out) {
		synchronized (out) {
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	private static void waitFor(java.util.function.BooleanSupplier cond, String what) throws InterruptedException {
		long end = System.currentTimeMillis()+10_000;
		while(!cond.getAsBoolean()) {
			assertTrue(System.currentTimeMillis() < end, "timed out waiting for "+what);
			Thread.sleep(20);
		}
	}

	@Test
	public void runsOnStreamsAndLeavesLaterKeysUnread() throws Exception {
		FileSource dir = FileOps.fromLocalFile(tmp.toFile());
		dir.getChild("hello.txt").createNewFile();
		dir.getChild("zz-dir").mkdirs();

		Keyboard in = new Keyboard();
		OutputStream keys = in.typing();
		ByteArrayOutputStream out = new ByteArrayOutputStream() {
			@Override
			public synchronized void write(byte[] b, int off, int len) {
				super.write(b, off, len);
			}
		};
		AtomicReference<Throwable> failed = new AtomicReference<>();
		AtomicReference<FileSource> ended = new AtomicReference<>();
		Thread run = new Thread(()->{
			try {
				ended.set(TermMain.run(dir, dir, true, new StreamTerminal(in, out, 80, 24)));
			} catch (Throwable t) {
				failed.set(t);
			}
		});
		run.start();

		waitFor(()->text(out).contains("hello.txt"), "the listing");
		assertTrue(text(out).contains("\u001b[?1049h"), "alternate screen");
		// arrow keys arrive as escape sequences and are parsed by TamboUI
		send(keys, "\u001b[B");          // down: zz-dir (directories first)
		send(keys, "\r");                // Enter: into it
		// (only the cells that change are sent, so the status line, not "(empty)", says it's there)
		waitFor(()->text(out).contains("0 item(s)"), "the empty directory");
		send(keys, "q");
		run.join(10_000);
		assertFalse(run.isAlive(), "still running");
		assertNull(failed.get());
		assertTrue(ended.get().getAbsolutePath().endsWith("zz-dir"), ended.get().getAbsolutePath());
		assertTrue(text(out).contains("\u001b[?1049l"), "left the alternate screen");
		assertTrue(text(out).contains("\u001b[?25h\u001b[?1049l"), "cursor shown again at the end");

		// a key typed after it ended stays for the next reader (fsh)
		Thread.sleep(200);
		send(keys, "x");
		Thread.sleep(200);
		assertEquals(1, in.available());
		assertEquals('x', in.read());
	}

	/** What's typed waits here until read; available() says how much (like a terminal). */
	private static final class Keyboard extends InputStream {
		private final LinkedBlockingQueue<Integer> typed = new LinkedBlockingQueue<>();

		OutputStream typing() {
			return new OutputStream() {
				@Override
				public void write(int b) {
					typed.add(b & 0xFF);
				}
			};
		}

		@Override
		public int available() {
			return typed.size();
		}

		@Override
		public int read() {
			try {
				return typed.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return -1;
			}
		}
	}

	private static void send(OutputStream keys, String text) throws Exception {
		keys.write(text.getBytes(StandardCharsets.UTF_8));
		keys.flush();
		Thread.sleep(100); // one key at a time, as typed
	}
}
