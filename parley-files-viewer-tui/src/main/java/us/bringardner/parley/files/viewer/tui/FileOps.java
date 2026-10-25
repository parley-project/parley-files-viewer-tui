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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.BooleanSupplier;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;

/**
 * Small helpers the TUI uses that have nothing to do with the screen: formatting, recursive
 * delete, and telling a local file from a remote one.
 */
public final class FileOps {

	private FileOps() {
	}

	/** 512 B, 1.5 K, 23 M, 4.0 G: short enough for a table column. */
	public static String formatSize(long bytes) {
		if( bytes < 1024 ) {
			return bytes+" B";
		}
		String units = "KMGTPE";
		double value = bytes;
		int idx = -1;
		while( value >= 1024 && idx < units.length()-1 ) {
			value /= 1024;
			idx++;
		}
		return (value < 10 ? String.format("%.1f", value) : String.format("%.0f", value))+" "+units.charAt(idx);
	}


	/**
	 * file's path relative to root (sub/deep.txt for root/sub/deep.txt), as search results show it,
	 * or its absolute path if it isn't under root.
	 */
	public static String relativePath(FileSource root, FileSource file) {
		String r = root.getAbsolutePath();
		String f = file.getAbsolutePath();
		if( f.length() > r.length() && f.startsWith(r)) {
			char c = f.charAt(r.length());
			if( r.endsWith("/") || r.endsWith("\\")) {
				return f.substring(r.length());
			}
			if( c == '/' || c == '\\' ) {
				return f.substring(r.length()+1);
			}
		}
		return f;
	}

	/** yyyy-MM-dd HH:mm, or blank when the time isn't known. */
	public static String formatDate(long millis) {
		if( millis <= 0 ) {
			return "";
		}
		return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
	}

	/**
	 * Delete a file, or a directory and everything in it.
	 *
	 * @param canceled checked before each file; when it says true the delete stops (what's
	 *        deleted stays deleted)
	 * @return the number of files and directories deleted
	 * @throws IOException naming the first one that couldn't be deleted
	 */
	public static int deleteRecursive(FileSource file, BooleanSupplier canceled) throws IOException {
		if( canceled != null && canceled.getAsBoolean()) {
			return 0;
		}
		int count = 0;
		if( file.isDirectory()) {
			FileSource[] kids = file.listFiles();
			if( kids != null ) {
				for(FileSource kid : kids) {
					count += deleteRecursive(kid, canceled);
				}
			}
		}
		if( canceled != null && canceled.getAsBoolean()) {
			return count;
		}
		if( !file.delete() && file.exists()) {
			throw new IOException("Can't delete "+file.getAbsolutePath());
		}
		return count+1;
	}

	/** True when this file is on the local disk (the fileproxy file system). */
	public static boolean isLocal(FileSource file) {
		return file != null && FileProxyFactory.FACTORY_ID.equals(file.getFileSourceFactory().getTypeId());
	}

	/** The java.io.File for a local FileSource. */
	public static File toLocalFile(FileSource file) {
		if( !isLocal(file)) {
			throw new IllegalArgumentException(file.getAbsolutePath()+" is not a local file");
		}
		return new File(file.getAbsolutePath());
	}

	/** The FileSource for a local java.io.File. */
	public static FileSource fromLocalFile(File file) throws IOException {
		return FileSourceFactory.getDefaultFactory().createFileSource(file.getAbsolutePath());
	}

	/** A path typed by a person: ~ or ~/... is the home directory. */
	public static String expandHome(String path) {
		if( path == null ) {
			return null;
		}
		if( path.equals("~")) {
			return System.getProperty("user.home");
		}
		if( path.startsWith("~/") || path.startsWith("~"+File.separator)) {
			// (File puts the platform's separator between the names: "C:\Users\x\.ssh", not "C:\Users\x/.ssh")
			return new File(System.getProperty("user.home")+path.substring(1)).getPath();
		}
		return path;
	}

	/** A local path to show a person: under the home directory it starts with ~ (expandHome undoes it). */
	public static String abbreviateHome(String path) {
		String home = System.getProperty("user.home");
		if( path == null || home == null || home.isEmpty()) {
			return path;
		}
		if( path.equals(home)) {
			return "~";
		}
		if( path.startsWith(home+File.separator)) {
			// (shown with /, as it is typed: ~/.ssh/id_rsa)
			return "~"+path.substring(home.length()).replace(File.separatorChar, '/');
		}
		return path;
	}

	/** The user's home directory on the local disk. */
	public static FileSource localHome() throws IOException {
		return fromLocalFile(new File(System.getProperty("user.home")));
	}

	/**
	 * A one-line label for a file system: "Local" for the local disk, else its type, or its
	 * title when that says more (sftp://user@host:22). Titles such as the memory file system's
	 * "Local" are not used, so a pane never looks local when it isn't.
	 */
	public static String describe(FileSourceFactory factory) {
		String type = factory.getTypeId();
		if( FileProxyFactory.FACTORY_ID.equals(type)) {
			return "Local";
		}
		String title = null;
		try {
			title = factory.getTitle();
		} catch (RuntimeException e) {
			// a factory that isn't connected may not have one
		}
		return title != null && title.startsWith(type+":") ? title : type;
	}
}
