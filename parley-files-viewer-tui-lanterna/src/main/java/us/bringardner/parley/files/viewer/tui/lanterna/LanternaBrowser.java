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

import java.io.IOException;

import us.bringardner.fsh.NativeKeyboard;
import us.bringardner.fsh.ShellContext;
import us.bringardner.fsh.spi.BrowseRequest;
import us.bringardner.fsh.spi.BrowserProvider;
import us.bringardner.parley.files.FileSource;

/**
 * The Lanterna browser for fsh's browse (browse --with lanterna): the two-pane browser on the
 * shell's own file tree (mounts included). fsh lends it the terminal while it runs, as it does a
 * program it starts.
 */
public class LanternaBrowser implements BrowserProvider {

	@Override
	public String name() {
		return "lanterna";
	}

	@Override
	public String description() {
		return "the full-screen browser on Lanterna";
	}

	@Override
	public int priority() {
		// (after the browser on Parley's own terminal code: the one to use when both are installed)
		return PRIORITY_TERMINAL-10;
	}

	@Override
	public boolean available(ShellContext ctx) {
		return BrowserProvider.onTerminal(ctx);
	}

	@Override
	public FileSource browse(ShellContext ctx, BrowseRequest request) throws IOException {
		FileSource[] end = {null};
		Object user = new Object();
		NativeKeyboard.lendTerminal(user);
		try {
			Tui.run(screen->{
				TuiBrowser b = new TuiBrowser(screen, request.left, request.right, request.onePane);
				b.run();
				end[0] = b.getActiveDirectory();
			});
		} finally {
			NativeKeyboard.reclaimTerminal(user);
		}
		return end[0];
	}
}
