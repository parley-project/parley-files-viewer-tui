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
import java.util.List;

import us.bringardner.fsh.ShellCommand;
import us.bringardner.fsh.ShellContext;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.viewer.tui.Preview;
import us.bringardner.parley.files.viewer.tui.termui.TermMain;

/** view: a file in a full-screen pager, or printed (hex for binary files). */
public class View extends ShellCommand {

	static final String NAME = "view";
	static final String HELP = "view [-c] [-x] FILE ...\n"
			+ "\tShow each FILE, on any mounted file system, in a full-screen viewer (Esc or q to\n"
			+ "\tclose). A binary file is shown as a hex dump. Only the first 1 MB is read.\n"
			+ "\tWhen the output is redirected or piped, or with -c, the files are printed instead.\n"
			+ "\t-c   print, don't page\n"
			+ "\t-x   hex dump even if the file looks like text";

	public View() {
		super(NAME, HELP);
	}

	@Override
	public int process(ShellContext ctx) throws IOException {
		FshArgs a;
		try {
			a = FshArgs.parse(NAME, FshArgs.words(args, ctx), "cx", "");
		} catch (IOException e) {
			ctx.error(e.getMessage());
			return 2;
		}
		if( a.words().isEmpty()) {
			ctx.error(NAME+": usage: view [-c] [-x] FILE ...");
			return 2;
		}
		boolean page = !a.has('c') && FshTerminal.isInteractive(ctx);
		int ret = 0;
		for(String path : a.words()) {
			List<FileSource> files = getFiles(ctx, path);
			for(FileSource f : files) {
				try {
					if( !f.exists()) {
						ctx.error(NAME+": "+path+": No such file or directory");
						ret = 1;
						continue;
					}
					if( f.isDirectory()) {
						ctx.error(NAME+": "+path+": Is a directory");
						ret = 1;
						continue;
					}
					Preview p = Preview.of(f, Preview.DEFAULT_LIMIT, a.has('x'));
					if( page ) {
						String title = f.getName()+(p.isBinary() ? " (hex)" : "")+(p.isTruncated() ? " (first 1 MB)" : "");
						TermMain.showText(title, p.getText(), FshTerminal.terminal(ctx));
					} else {
						ctx.stdout.print(p.getText());
						if( !p.getText().isEmpty() && !p.getText().endsWith("\n")) {
							ctx.stdout.println();
						}
						if( p.isTruncated()) {
							ctx.error(NAME+": "+path+": only the first 1 MB was shown");
						}
					}
				} catch (IOException e) {
					ctx.error(NAME+": "+path+": "+e.getMessage());
					ret = 1;
				}
			}
		}
		ctx.stdout.flush();
		return ret;
	}
}
