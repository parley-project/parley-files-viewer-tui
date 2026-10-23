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
import us.bringardner.parley.files.viewer.tui.EditSession;
import us.bringardner.parley.files.viewer.tui.FileOps;

/** edit: any file, on any mounted file system, with $VISUAL / $EDITOR. */
public class Edit extends ShellCommand {

	static final String NAME = "edit";
	static final String HELP = "edit FILE\n"
			+ "\tEdit FILE with $VISUAL, else $EDITOR, else vi. A file on a mounted remote file system\n"
			+ "\t(connect) is copied to a temporary local file for the editor and copied back if it was\n"
			+ "\tsaved; if it was changed on the server meanwhile, edit asks before replacing it. A file\n"
			+ "\tthat doesn't exist is created when it's saved.";

	public Edit() {
		super(NAME, HELP);
	}

	@Override
	public int process(ShellContext ctx) throws IOException {
		String[] words = FshArgs.words(args, ctx);
		if( words.length != 1 ) {
			ctx.error(NAME+": usage: edit FILE");
			return 2;
		}
		List<FileSource> files = getFiles(ctx, words[0]);
		if( files.size() != 1 ) {
			ctx.error(NAME+": "+words[0]+": names "+files.size()+" files");
			return 2;
		}
		FileSource file = files.get(0);
		// (a file under a mount reports the mounted file system, so a remote one is staged)
		FileSource real = file;
		try (EditSession session = EditSession.open(real)) {
			StringBuilder cmd = new StringBuilder();
			for(String part : EditSession.editorCommand(session.getLocalFile())) {
				if( cmd.length() > 0 ) {
					cmd.append(' ');
				}
				cmd.append(FshTerminal.quote(part));
			}
			// fsh starts the editor, so it gets the terminal as any program would
			int code = ctx.console.executeScript(ctx, cmd.toString());
			if( code != 0 ) {
				ctx.error(NAME+": the editor exited with "+code+"; "+words[0]+(session.isRemote() ? " was not copied back" : ""));
				return code;
			}
			if( !session.isRemote()) {
				return 0;
			}
			if( !session.isChanged()) {
				ctx.stderr.println(NAME+": "+words[0]+": not changed");
				return 0;
			}
			if( session.isRemoteChanged()) {
				String answer = promptAndGetReponse(ctx.stderr, ctx.stdin,
						NAME+": "+words[0]+" was changed on the server while you edited it. Replace it? [y/N] ");
				if( answer == null || !answer.trim().toLowerCase().startsWith("y")) {
					ctx.stderr.println(NAME+": your edit was not saved");
					return 1;
				}
			}
			session.writeBack();
			ctx.stderr.println(NAME+": saved "+words[0]+" to "+FileOps.describe(real.getFileSourceFactory()));
			return 0;
		} catch (IOException e) {
			ctx.error(NAME+": "+words[0]+": "+e.getMessage());
			return 1;
		}
	}
}
