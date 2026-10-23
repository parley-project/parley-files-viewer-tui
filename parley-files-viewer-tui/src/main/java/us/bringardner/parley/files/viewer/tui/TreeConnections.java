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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.tree.VirtualFileSourceFactory;

/**
 * The browser's own file tree, when nothing it runs in gives it one (pfv): the local disk, and
 * the file systems connected in the browser at paths (/sftp-build), as fsh's shell has them. So
 * the browser works the same on its own as in fsh; its connections close when it ends.
 */
public final class TreeConnections implements HostConnections {

	private final VirtualFileSourceFactory tree;

	public TreeConnections() throws IOException {
		this(new VirtualFileSourceFactory());
	}

	public TreeConnections(VirtualFileSourceFactory tree) {
		this.tree = tree;
	}

	@Override
	public Map<String, FileSource> list() {
		return tree.connections();
	}

	@Override
	public boolean connect(String path, FileSource top) throws IOException {
		return tree.mount(path, top);
	}

	@Override
	public void disconnect(String path) throws IOException {
		FileSource removed = tree.unmount(path);
		if( removed == null ) {
			throw new IOException("nothing is connected at "+path);
		}
		FileSourceFactory factory = removed.getFileSourceFactory();
		if( factory != null && !tree.uses(factory)) {
			factory.disConnect();
		}
	}

	@Override
	public FileSource file(String path) throws IOException {
		return tree.createFileSource(path);
	}

	/** Disconnect everything (the browser is ending). */
	public void disconnectAll() {
		for(String path : tree.connections().keySet()) {
			try {
				disconnect(path);
			} catch (IOException e) {
				// it's being let go anyway
			}
		}
	}
}
