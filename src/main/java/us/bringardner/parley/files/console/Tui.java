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
package us.bringardner.parley.files.console;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

/**
 * Starts a full-screen session on the real terminal and puts the terminal back afterwards.
 * Reads and writes the process's own descriptors 0 and 1 rather than System.in and System.out,
 * which a host such as fsh may have replaced.
 */
public final class Tui {

	public interface Body {
		void run(Screen screen) throws IOException;
	}

	private Tui() {
	}

	public static void run(Body body) throws IOException {
		Charset utf8 = StandardCharsets.UTF_8;
		Terminal terminal = new DefaultTerminalFactory(
				new FileOutputStream(FileDescriptor.out), new FileInputStream(FileDescriptor.in), utf8)
				.setForceTextTerminal(true)
				.createTerminal();
		Screen screen = new TerminalScreen(terminal);
		screen.startScreen();
		try {
			body.run(screen);
		} finally {
			screen.stopScreen();
			terminal.flush();
		}
	}
}
