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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceCopy;

/**
 * Lets a terminal editor ({@code $VISUAL}, {@code $EDITOR}) work on any file. A local file is
 * edited where it is. A remote one is copied to a temporary local file first; after the editor
 * exits, {@link #isChanged()} says whether it was saved, and {@link #writeBack()} copies it back.
 * {@link #isRemoteChanged()} warns when someone else changed the remote file meanwhile.
 */
public final class EditSession implements AutoCloseable {

	private final FileSource file;
	private final File localFile;
	private final File tempDir;
	private final byte[] startHash;
	private final long remoteModified;
	private final long remoteLength;

	private EditSession(FileSource file, File localFile, File tempDir) throws IOException {
		this.file = file;
		this.localFile = localFile;
		this.tempDir = tempDir;
		this.startHash = hash(localFile);
		this.remoteModified = tempDir == null ? 0 : file.lastModified();
		this.remoteLength = tempDir == null ? 0 : file.length();
	}

	/** Start editing file: stages it locally if it isn't local already. */
	public static EditSession open(FileSource file) throws IOException {
		if( file.isDirectory()) {
			throw new IOException(file.getAbsolutePath()+" is a directory");
		}
		if( FileOps.isLocal(file)) {
			return new EditSession(file, FileOps.toLocalFile(file), null);
		}
		File dir = Files.createTempDirectory("parley-edit").toFile();
		File local = new File(dir, file.getName());
		if( file.exists()) {
			FileSourceCopy.copy(file, FileOps.fromLocalFile(local));
		} else if( !local.createNewFile()) {
			throw new IOException("Can't create "+local);
		}
		return new EditSession(file, local, dir);
	}

	public FileSource getFile() {
		return file;
	}

	/** The file the editor should open. */
	public File getLocalFile() {
		return localFile;
	}

	/** True when the file was copied here from another file system. */
	public boolean isRemote() {
		return tempDir != null;
	}

	/** True if the local copy's content is different from when the session started. */
	public boolean isChanged() throws IOException {
		return !Arrays.equals(startHash, hash(localFile));
	}

	/** True if the remote file was changed by someone else since it was copied here. */
	public boolean isRemoteChanged() throws IOException {
		return isRemote() && (file.lastModified() != remoteModified || file.length() != remoteLength);
	}

	/** Copy the edited file back over the remote one. Nothing to do for a local file. */
	public void writeBack() throws IOException {
		if( isRemote()) {
			FileSourceCopy.copy(FileOps.fromLocalFile(localFile), file);
		}
	}

	/** Remove the temporary copy, if there is one. */
	@Override
	public void close() {
		if( tempDir != null ) {
			File[] kids = tempDir.listFiles();
			if( kids != null ) {
				for(File kid : kids) {
					kid.delete();
				}
			}
			tempDir.delete();
		}
	}

	/** The command that edits a file: $VISUAL, else $EDITOR, else vi (notepad on Windows). */
	public static List<String> editorCommand(File file) {
		String editor = System.getenv("VISUAL");
		if( editor == null || editor.trim().isEmpty()) {
			editor = System.getenv("EDITOR");
		}
		if( editor == null || editor.trim().isEmpty()) {
			editor = isWindows() ? "notepad" : "vi";
		}
		List<String> ret = new ArrayList<>(Arrays.asList(editor.trim().split("\\s+")));
		ret.add(file.getAbsolutePath());
		return ret;
	}

	/** The command that opens a shell: $SHELL, else /bin/sh (cmd.exe on Windows). */
	public static List<String> shellCommand() {
		if( isWindows()) {
			String comspec = System.getenv("COMSPEC");
			return Arrays.asList(comspec == null ? "cmd.exe" : comspec);
		}
		String shell = System.getenv("SHELL");
		return Arrays.asList(shell == null || shell.isEmpty() ? "/bin/sh" : shell);
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static byte[] hash(File file) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(file.toPath())) {
				byte[] buf = new byte[64*1024];
				int n;
				while( (n = in.read(buf)) > 0 ) {
					md.update(buf, 0, n);
				}
			}
			return md.digest();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}
}
