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

import us.bringardner.fsh.ShellCommand;
import us.bringardner.fsh.ShellContext;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.viewer.tui.TuiBrowser;

/** browse: the full-screen two-pane browser, started on fsh's own file tree (mounts included). */
public class Browse extends ShellCommand {

	static final String NAME = "browse";
	static final String HELP = "browse [-1] [-c] [DIR [DIR2]]\n"
			+ "\tOpen the full-screen file browser with DIR (default .) on the left and DIR2 (default\n"
			+ "\t$HOME) on the right. It sees the shell's file tree, so file systems mounted with\n"
			+ "\tconnect are under their mount points. q (or F10) comes back to the shell; ? lists\n"
			+ "\tthe browser's keys.\n"
			+ "\t-1   start with one pane (w switches between one and two)\n"
			+ "\t-c   when you quit, cd to the directory you were in";

	public Browse() {
		super(NAME, HELP);
	}

	@Override
	public int process(ShellContext ctx) throws IOException {
		FshArgs a;
		try {
			a = FshArgs.parse(NAME, FshArgs.words(args, ctx), "1c", "");
		} catch (IOException e) {
			ctx.error(e.getMessage());
			return 2;
		}
		if( a.words().size() > 2 ) {
			ctx.error(NAME+": usage: browse [-1] [-c] [DIR [DIR2]]");
			return 2;
		}
		if( !FshTerminal.isInteractive(ctx)) {
			ctx.error(NAME+": needs the terminal (its output is redirected or piped)");
			return 1;
		}
		FileSource left = a.words().size() > 0 ? ctx.console.createFileSource(a.words().get(0)) : ctx.console.getCurrentDirectory();
		Object home = ctx.getVariable("HOME");
		FileSource right = a.words().size() > 1 ? ctx.console.createFileSource(a.words().get(1))
				: home != null && !home.toString().isEmpty() ? ctx.console.createFileSource(home.toString()) : left;
		for(FileSource f : new FileSource[] {left, right}) {
			if( !f.isDirectory()) {
				ctx.error(NAME+": "+f.getAbsolutePath()+": Not a directory");
				return 1;
			}
		}
		FileSource[] end = {null};
		FshTerminal.run(ctx, screen->{
			TuiBrowser b = new TuiBrowser(screen, left, right, a.has('1'));
			b.run();
			end[0] = b.getActiveDirectory();
		});
		if( a.has('c') && end[0] != null ) {
			// the shell's own path for it: a directory on a file system connected in the browser isn't in the tree
			FileSource dir = ctx.console.createFileSource(end[0].getAbsolutePath());
			if( dir.isDirectory() && dir.getFileSourceFactory().isSameFileSystem(end[0].getFileSourceFactory())) {
				ctx.console.setCurrentDirectory(dir);
			} else {
				ctx.error(NAME+": "+end[0].getAbsolutePath()+" isn't in the shell's file tree (connect it first)");
				return 1;
			}
		}
		return 0;
	}
}
