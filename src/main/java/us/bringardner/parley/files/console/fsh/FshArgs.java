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
package us.bringardner.parley.files.console.fsh;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import us.bringardner.fsh.ShellContext;

/**
 * Small option parser for the console's fsh builtins: single-letter flags (which may be
 * combined, -ia), flags that take a value (-n PATTERN or -nPATTERN), {@code --} to end options,
 * and the remaining words.
 */
final class FshArgs {

	private final List<Character> flags = new ArrayList<>();
	private final java.util.Map<Character, String> values = new java.util.HashMap<>();
	private final List<String> words = new ArrayList<>();

	/**
	 * @param command for error messages
	 * @param booleans the flags that take no value
	 * @param valued the flags that take a value
	 */
	static FshArgs parse(String command, String[] args, String booleans, String valued) throws IOException {
		FshArgs ret = new FshArgs();
		boolean ended = false;
		for(int idx=0; idx < args.length; idx++) {
			String a = args[idx];
			if( ended || !a.startsWith("-") || a.equals("-")) {
				ret.words.add(a);
				continue;
			}
			if( a.equals("--")) {
				ended = true;
				continue;
			}
			for(int c=1; c < a.length(); c++) {
				char f = a.charAt(c);
				if( booleans.indexOf(f) >= 0 ) {
					ret.flags.add(f);
				} else if( valued.indexOf(f) >= 0 ) {
					String v;
					if( c+1 < a.length()) {
						v = a.substring(c+1);
					} else if( idx+1 < args.length ) {
						v = args[++idx];
					} else {
						throw new IOException(command+": -"+f+": option requires an argument");
					}
					ret.values.put(f, v);
					break;
				} else {
					throw new IOException(command+": -"+f+": invalid option");
				}
			}
		}
		return ret;
	}

	/** The words of a builtin's arguments, expanded as the shell gave them. */
	static String[] words(us.bringardner.fsh.Argument[] args, ShellContext ctx) throws IOException {
		String[] ret = new String[args == null ? 0 : args.length];
		for(int idx=0; idx < ret.length; idx++) {
			Object v = args[idx].getValue(ctx);
			ret[idx] = v == null ? "" : v.toString();
		}
		return ret;
	}

	boolean has(char flag) {
		return flags.contains(flag);
	}

	String value(char flag) {
		return values.get(flag);
	}

	List<String> words() {
		return words;
	}
}
