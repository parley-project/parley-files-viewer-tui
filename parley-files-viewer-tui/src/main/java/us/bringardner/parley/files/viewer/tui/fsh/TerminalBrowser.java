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
import us.bringardner.fsh.spi.BrowseRequest;
import us.bringardner.fsh.spi.BrowserProvider;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.viewer.tui.termui.TermMain;

/**
 * The full-screen browser on Parley's own terminal code (parley-term), for fsh's browse: the
 * shell's own file tree, mounts included.
 */
public class TerminalBrowser implements BrowserProvider {

	@Override
	public String name() {
		return "terminal";
	}

	@Override
	public String description() {
		return "the full-screen browser on Parley's own terminal code";
	}

	@Override
	public int priority() {
		return PRIORITY_TERMINAL;
	}

	@Override
	public boolean available(ShellContext ctx) {
		return BrowserProvider.onTerminal(ctx);
	}

	@Override
	public FileSource browse(ShellContext ctx, BrowseRequest request) throws IOException {
		try {
			return TermMain.run(request.left, request.right, request.onePane, FshTerminal.terminal(ctx),
					FshTerminal.connections(request.connections));
		} catch (IOException | RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException(e);
		}
	}
}
