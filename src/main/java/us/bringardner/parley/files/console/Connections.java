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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * Opening file systems: which kinds are installed, what each needs to connect, and connecting.
 * The kinds come from the FileSourceFactory registrations on the class path, so a new
 * parley-files module (FTP, say) shows up without a change here.
 */
public final class Connections {

	private Connections() {
	}

	/** The type ids of the installed file systems, sorted, local first. */
	public static List<String> types() {
		List<String> ret = new ArrayList<>(Arrays.asList(FileSourceFactory.getRegisterdFactories()));
		ret.sort((a, b)->{
			if( a.equals(b)) {
				return 0;
			}
			if( a.equals(FileProxyFactory.FACTORY_ID)) {
				return -1;
			}
			if( b.equals(FileProxyFactory.FACTORY_ID)) {
				return 1;
			}
			return a.compareTo(b);
		});
		return ret;
	}

	/** A new, unconnected factory of this type. */
	public static FileSourceFactory create(String typeId) throws IOException {
		FileSourceFactory ret = FileSourceFactory.getFileSourceFactory(typeId);
		if( ret == null ) {
			throw new IOException("No file system of type "+typeId+" is installed");
		}
		return ret;
	}

	/** The settings a form should ask for: the factory's settings minus hidden ones. */
	public static List<ConnectionSetting> formSettings(FileSourceFactory factory) {
		List<ConnectionSetting> ret = new ArrayList<>();
		for(ConnectionSetting s : factory.getConnectionSettings()) {
			if( s.kind() != ConnectionSetting.Kind.HIDDEN ) {
				ret.add(s);
			}
		}
		return ret;
	}

	/** The values a form starts with: the factory's current properties over the defaults. */
	public static Properties initialValues(FileSourceFactory factory) {
		return ConnectionSettings.initialValues(factory.getConnectionSettings(), factory.getConnectProperties());
	}

	/**
	 * Check the values, connect, and return the directory to show first.
	 *
	 * @throws IOException with one line per problem if the values aren't valid, or if the
	 *         connect fails
	 */
	public static FileSource connect(FileSourceFactory factory, Properties values) throws IOException {
		List<ConnectionSetting> settings = factory.getConnectionSettings();
		List<String> problems = factory.validateConnection(values);
		if( !problems.isEmpty()) {
			throw new IOException(String.join("\n", problems));
		}
		if( !factory.connect(ConnectionSettings.forConnect(settings, values))) {
			throw new IOException("Could not connect to "+factory.getTypeId());
		}
		return startDirectory(factory);
	}

	/** The factory's current directory, else its first root. */
	public static FileSource startDirectory(FileSourceFactory factory) throws IOException {
		FileSource ret = factory.getCurrentDirectory();
		if( ret == null ) {
			FileSource[] roots = factory.listRoots();
			if( roots == null || roots.length == 0 ) {
				throw new IOException(factory.getTypeId()+" has no root directory");
			}
			ret = roots[0];
		}
		return ret;
	}
}
