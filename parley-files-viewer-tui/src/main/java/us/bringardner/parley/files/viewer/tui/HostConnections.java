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

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.FileSource;

/**
 * The connections of the program the browser runs in (fsh's connect and disconnect): a file system
 * connected in the browser is connected there, so it stays after the browser ends. On its own the
 * browser has a tree of its own ({@link TreeConnections}), which ends with it.
 */
public interface HostConnections {

	/** the path each connection is at (/mem), and the top folder of the file system connected there */
	Map<String, FileSource> list();

	/**
	 * Connect top (the top folder of a connected file system) at path.
	 * @return false if something is already connected at path
	 */
	boolean connect(String path, FileSource top) throws IOException;

	/** Disconnect what is at path: its file system is closed if nothing else uses it. */
	void disconnect(String path) throws IOException;

	/** the file at path in the host's file tree (/mem/x) */
	FileSource file(String path) throws IOException;

	/**
	 * Where to connect a file system by default: /TYPE, or /TYPE-HOST when it has a host
	 * (/sftp-build), with -2, -3 ... added if that path is taken.
	 */
	static String suggestPath(String typeId, Properties values, java.util.Set<String> taken) {
		String host = values == null ? null : values.getProperty("host");
		String base = "/"+clean(typeId)+(host == null || host.trim().isEmpty() ? "" : "-"+clean(host.trim()));
		String ret = base;
		for(int n = 2; taken.contains(ret); n++) {
			ret = base+"-"+n;
		}
		return ret;
	}

	/** a path's problem as a place to connect at, or null if it's fine */
	static String checkPath(String path, java.util.Set<String> taken) {
		if( path.isEmpty() || path.charAt(0) != '/' && !us.bringardner.parley.files.tree.VirtualFileSourceFactory.isDrivePath(path) || path.equals("/")) {
			return "Connect at: a path such as /sftp-build";
		}
		if( taken.contains(path)) {
			return "Connect at: something is already connected at "+path;
		}
		return null;
	}

	private static String clean(String s) {
		return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
	}
}
