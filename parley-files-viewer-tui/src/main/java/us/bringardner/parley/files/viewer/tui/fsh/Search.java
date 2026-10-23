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
import java.util.ArrayList;
import java.util.List;

import us.bringardner.fsh.ShellCommand;
import us.bringardner.fsh.ShellContext;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.viewer.tui.SearchJob;

/** search: files below directories by name and/or contents, on any mounted file system. */
public class Search extends ShellCommand {

	static final String NAME = "search";
	static final String HELP = "search [-i] [-a] [-l] [-n NAME] [-t TEXT] [DIR ...]\n"
			+ "\tList the files below each DIR (default .) whose name matches the glob NAME and that\n"
			+ "\tcontain TEXT; with neither, every file and directory. Paths are printed as find prints\n"
			+ "\tthem (starting with DIR), followed by the first matching line for a text search.\n"
			+ "\tWorks the same on every mounted file system. Files over 32 MB aren't searched for text.\n"
			+ "\t-n NAME   file name glob (* ? [abc]); quote it so the shell doesn't expand it\n"
			+ "\t-t TEXT   text the file must contain\n"
			+ "\t-i        ignore case (in NAME and TEXT)\n"
			+ "\t-a        include hidden files and directories\n"
			+ "\t-l        print only the paths, not the matching lines";

	public Search() {
		super(NAME, HELP);
	}

	@Override
	public int process(ShellContext ctx) throws IOException {
		FshArgs a;
		try {
			a = FshArgs.parse(NAME, FshArgs.words(args, ctx), "ial", "nt");
		} catch (IOException e) {
			ctx.error(e.getMessage());
			return 2;
		}
		List<String> dirs = new ArrayList<>(a.words());
		if( dirs.isEmpty()) {
			dirs.add(".");
		}
		int[] found = {0};
		int[] errors = {0};
		for(String dirArg : dirs) {
			FileSource dir = ctx.console.createFileSource(dirArg);
			if( !dir.isDirectory()) {
				ctx.error(NAME+": "+dirArg+": Not a directory");
				errors[0]++;
				continue;
			}
			String base = dir.getAbsolutePath();
			String shown = dirArg.endsWith("/") && dirArg.length() > 1 ? dirArg.substring(0, dirArg.length()-1) : dirArg;
			SearchJob job = new SearchJob(dir, a.value('n'), a.value('t'), a.has('i'), a.has('a'), new SearchJob.Listener() {
				@Override
				public void found(FileSource file, String line) {
					found[0]++;
					String path = file.getAbsolutePath();
					String rel = path.startsWith(base) ? path.substring(base.length()) : "/"+file.getName();
					if( !rel.startsWith("/")) {
						rel = "/"+rel;
					}
					String out = (shown.equals("/") ? "" : shown)+rel;
					ctx.stdout.println(line == null || a.has('l') ? out : out+": "+line);
				}

				@Override
				public void error(FileSource file, IOException e) {
					errors[0]++;
					ctx.error(NAME+": "+file.getAbsolutePath()+": "+e.getMessage());
				}
			});
			job.run();
		}
		ctx.stdout.flush();
		// as grep: 0 found something, 1 found nothing, 2 trouble
		return errors[0] > 0 && found[0] == 0 ? 2 : found[0] > 0 ? 0 : 1;
	}
}
