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

import us.bringardner.fsh.Console;
import us.bringardner.fsh.ShellCommand;

/**
 * The line-oriented version of the TUI: fsh (a bash-compatible shell over any parley-files
 * file system: connect, cd, ls, cp, find ...), with the file viewer's other features added as
 * builtins:
 * <ul>
 * <li>{@code view}: a file in a full-screen pager (hex for binary files)</li>
 * <li>{@code edit}: any file with $EDITOR; remote ones are staged locally and copied back</li>
 * <li>{@code search}: by name and contents, on any mounted file system</li>
 * <li>{@code browse}: the full-screen two-pane browser on the shell's file tree</li>
 * </ul>
 * {@code help NAME} describes each. Nothing in fsh changes: the builtins are added to its
 * table before it starts.
 */
public final class FshTui {

	private FshTui() {
	}

	/** Add the builtins to fsh's command table (before a Console runs). */
	public static void install() {
		add(View.class);
		add(Edit.class);
		add(Search.class);
		add(Browse.class);
	}

	private static void add(Class<? extends ShellCommand> cls) {
		try {
			ShellCommand cmd = cls.getConstructor().newInstance();
			Console.commands.put(cmd.getName(), cls.getConstructor());
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't add "+cls.getSimpleName()+" to fsh", e);
		}
	}

	public static void main(String[] args) throws IOException {
		install();
		Console.main(args);
	}
}
