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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Pattern;

import us.bringardner.parley.files.FileSource;

/**
 * Finds files under a directory by name (a glob such as {@code *.java}) and, optionally, by text
 * they contain. Walks breadth first, reports each match to a {@link Listener} as it's found, and
 * stops when canceled. Like {@link CopyJob} it runs on the caller's thread and never touches the
 * screen.
 */
public final class SearchJob implements Runnable {

	public interface Listener {
		/** A directory is about to be searched. */
		default void searching(FileSource dir) {
		}

		/** A match: the file, and for a text search the first matching line (else null). */
		void found(FileSource file, String line);

		/** A directory or file couldn't be read; the search carries on. */
		default void error(FileSource file, IOException e) {
		}

		/** The search is over. */
		default void finished(int matches, boolean canceled) {
		}
	}

	/** Files bigger than this aren't searched for text. */
	public static final long DEFAULT_MAX_TEXT_SIZE = 32L*1024*1024;

	private final FileSource root;
	private final Pattern name;
	private final String text;
	private final boolean ignoreCase;
	private final boolean includeHidden;
	private final Listener listener;
	private long maxTextSize = DEFAULT_MAX_TEXT_SIZE;
	private volatile boolean canceled;
	private int matches;

	/**
	 * @param root where to start
	 * @param glob file name pattern: {@code *} and {@code ?} wildcards, {@code [abc]} sets;
	 *        null or empty matches every name
	 * @param text text the file must contain; null or empty for any
	 */
	public SearchJob(FileSource root, String glob, String text, boolean ignoreCase, boolean includeHidden, Listener listener) {
		this.root = root;
		this.ignoreCase = ignoreCase;
		this.name = glob == null || glob.trim().isEmpty() ? null : globToPattern(glob.trim(), ignoreCase);
		this.text = text == null || text.isEmpty() ? null : (ignoreCase ? text.toLowerCase(Locale.ROOT) : text);
		this.includeHidden = includeHidden;
		this.listener = listener;
	}

	public void setMaxTextSize(long maxTextSize) {
		this.maxTextSize = maxTextSize;
	}

	public void cancel() {
		canceled = true;
	}

	public boolean isCanceled() {
		return canceled;
	}

	@Override
	public void run() {
		Deque<FileSource> dirs = new ArrayDeque<>();
		dirs.add(root);
		try {
			while( !dirs.isEmpty() && !canceled ) {
				FileSource dir = dirs.poll();
				listener.searching(dir);
				FileSource[] kids;
				try {
					kids = dir.listFiles();
				} catch (IOException e) {
					listener.error(dir, e);
					continue;
				}
				if( kids == null ) {
					continue;
				}
				for(FileSource kid : kids) {
					if( canceled ) {
						break;
					}
					try {
						if( !includeHidden && kid.isHidden()) {
							continue;
						}
						boolean isDir = kid.isDirectory();
						if( isDir ) {
							dirs.add(kid);
						}
						if( name != null && !name.matcher(kid.getName()).matches()) {
							continue;
						}
						if( text == null ) {
							matches++;
							listener.found(kid, null);
						} else if( !isDir ) {
							String line = findText(kid);
							if( line != null ) {
								matches++;
								listener.found(kid, line);
							}
						}
					} catch (IOException e) {
						listener.error(kid, e);
					}
				}
			}
		} finally {
			listener.finished(matches, canceled);
		}
	}

	private String findText(FileSource file) throws IOException {
		if( file.length() > maxTextSize ) {
			return null;
		}
		try (BufferedReader in = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while( (line = in.readLine()) != null ) {
				if( canceled ) {
					return null;
				}
				String test = ignoreCase ? line.toLowerCase(Locale.ROOT) : line;
				if( test.contains(text)) {
					return line.trim();
				}
			}
		}
		return null;
	}

	/** {@code *.java} to a regular expression that matches whole names. */
	public static Pattern globToPattern(String glob, boolean ignoreCase) {
		StringBuilder re = new StringBuilder();
		boolean inSet = false;
		for(char c : glob.toCharArray()) {
			if( inSet ) {
				if( c == ']' ) {
					inSet = false;
					re.append(']');
				} else if( c == '\\' ) {
					re.append("\\\\");
				} else {
					re.append(c);
				}
				continue;
			}
			switch (c) {
				case '*': re.append(".*"); break;
				case '?': re.append('.'); break;
				case '[': inSet = true; re.append('['); break;
				default:
					if( "\\.^$|+(){}".indexOf(c) >= 0 ) {
						re.append('\\');
					}
					re.append(c);
			}
		}
		if( inSet ) {
			// an unclosed [ is taken literally
			int idx = re.lastIndexOf("[");
			re.insert(idx, '\\');
		}
		return Pattern.compile(re.toString(), ignoreCase ? Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE : 0);
	}
}
