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

import us.bringardner.fsh.NativeKeyboard;
import us.bringardner.fsh.ShellContext;
import us.bringardner.parley.files.viewer.tui.Tui;

/**
 * Runs a full-screen view from inside fsh: the shell lends the terminal (as it does to a
 * program it starts) for as long as the view runs, and gets it back afterwards.
 */
final class FshTerminal {

	private FshTerminal() {
	}

	/** True when the builtin's output is the shell's own (not redirected or piped). */
	static boolean isInteractive(ShellContext ctx) {
		return ctx.stdout == System.out && System.console() != null;
	}

	static void run(ShellContext ctx, Tui.Body body) throws IOException {
		ctx.stdout.flush();
		ctx.stderr.flush();
		Object user = new Object();
		NativeKeyboard.lendTerminal(user);
		try {
			Tui.run(body);
		} finally {
			NativeKeyboard.reclaimTerminal(user);
		}
	}

	/** 'text', quoted for the shell. */
	static String quote(String text) {
		return "'"+text.replace("'", "'\\''")+"'";
	}
}
