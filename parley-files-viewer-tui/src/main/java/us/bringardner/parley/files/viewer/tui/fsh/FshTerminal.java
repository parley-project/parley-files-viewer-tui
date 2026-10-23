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
package us.bringardner.parley.files.viewer.tui.fsh;

import java.io.IOException;

import us.bringardner.fsh.ShellContext;
import us.bringardner.fsh.spi.BrowserProvider;
import us.bringardner.parley.term.tty.NativeTerminal;
import us.bringardner.parley.term.tty.Terminal;

/**
 * A full-screen view from inside fsh, on parley-term: fsh's own terminal, already in raw mode and
 * reading Ctrl-C and Ctrl-Z itself, so the view leaves those to it.
 */
final class FshTerminal {

	private FshTerminal() {
	}

	/** True when the command's output is the shell's own terminal (not redirected or piped). */
	static boolean isInteractive(ShellContext ctx) {
		return BrowserProvider.onTerminal(ctx);
	}

	/** what a view inside fsh runs on */
	static Terminal terminal(ShellContext ctx) throws IOException {
		ctx.stdout.flush();
		ctx.stderr.flush();
		return new NativeTerminal(false);
	}

	/** 'text', quoted for the shell. */
	static String quote(String text) {
		return "'"+text.replace("'", "'\\''")+"'";
	}
}
