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
package us.bringardner.parley.files.viewer.tui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.tree.LocalFiles;

/**
 * What the panes show for an entry that isn't an ordinary file or folder, in place of its size or
 * &lt;DIR&gt;: a connection's file system (&lt;sftp&gt;, &lt;memory&gt;) and a local symbolic link
 * (&lt;LINK&gt;), with what each points at for the status line (mem -&gt; memory, latest -&gt;
 * releases/2.0.0), as fsh's ls -l shows them.
 */
public final class EntryMarks {

	/** the mark (&lt;sftp&gt;, &lt;LINK&gt;) and what it points at */
	public static final class Mark {
		public final String mark;
		public final String target;
		/** a connection (else a symbolic link) */
		public final boolean connection;

		Mark(String mark, String target, boolean connection) {
			this.mark = mark;
			this.target = target;
			this.connection = connection;
		}
	}

	private EntryMarks() {
	}

	/**
	 * The marks of files, by name: the connections among them (connections: the tree's, by path)
	 * and the local symbolic links. Files that are neither aren't in it.
	 */
	public static Map<String, Mark> of(FileSource[] files, Map<String, FileSource> connections) {
		Map<String, Mark> ret = new HashMap<>();
		if( files == null ) {
			return ret;
		}
		for(FileSource f : files) {
			Mark m = of(f, connections);
			if( m != null ) {
				ret.put(f.getName(), m);
			}
		}
		return ret;
	}

	static Mark of(FileSource f, Map<String, FileSource> connections) {
		FileSource top = connections == null ? null : connections.get(f.getAbsolutePath().replace('\\', '/'));
		if( top != null ) {
			return new Mark("<"+Connections.label(top.getFileSourceFactory().getTypeId())+">", describeConnection(top), true);
		}
		File local = LocalFiles.of(f);
		if( local != null ) {
			Path p = local.toPath();
			if( Files.isSymbolicLink(p)) {
				String target;
				try {
					target = Files.readSymbolicLink(p).toString();
				} catch (IOException | RuntimeException e) {
					target = "?";
				}
				return new Mark("<LINK>", target, false);
			}
		}
		return null;
	}

	/** a connection as fsh's connect and ls -l show it: the file system, and user@host:folder when it has a host */
	public static String describeConnection(FileSource top) {
		FileSourceFactory f = top.getFileSourceFactory();
		Properties p = f.getConnectProperties();
		String host = p == null ? null : p.getProperty("host");
		String user = p == null ? null : p.getProperty("user");
		String folder = top.getAbsolutePath();
		StringBuilder b = new StringBuilder(Connections.label(f.getTypeId()));
		if( host != null && !host.trim().isEmpty()) {
			b.append(' ');
			if( user != null && !user.trim().isEmpty()) {
				b.append(user.trim()).append('@');
			}
			b.append(host.trim()).append(':').append(folder);
		} else if( !folder.equals("/") ) {
			b.append(' ').append(folder);
		}
		return b.toString();
	}
}
