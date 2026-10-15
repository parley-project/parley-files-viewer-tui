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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import us.bringardner.parley.files.FileSource;

/**
 * The text the built-in viewer shows for a file: the file as text, or a hex dump when it looks
 * binary, limited to the first {@link #DEFAULT_LIMIT} bytes so a huge remote file isn't read
 * whole.
 */
public final class Preview {

	public static final int DEFAULT_LIMIT = 1024*1024;

	private final String text;
	private final boolean binary;
	private final boolean truncated;

	private Preview(String text, boolean binary, boolean truncated) {
		this.text = text;
		this.binary = binary;
		this.truncated = truncated;
	}

	public static Preview of(FileSource file) throws IOException {
		return of(file, DEFAULT_LIMIT);
	}

	public static Preview of(FileSource file, int limit) throws IOException {
		byte[] data;
		boolean truncated;
		try (InputStream in = file.getInputStream()) {
			data = in.readNBytes(limit);
			truncated = in.read() >= 0;
		}
		boolean binary = looksBinary(data);
		String text = binary ? hexDump(data) : new String(data, StandardCharsets.UTF_8).replace("\t", "    ").replace("\r", "");
		return new Preview(text, binary, truncated);
	}

	public String getText() {
		return text;
	}

	public boolean isBinary() {
		return binary;
	}

	public boolean isTruncated() {
		return truncated;
	}

	/** A NUL byte, or lots of control characters, in the first 8K means binary. */
	static boolean looksBinary(byte[] data) {
		int n = Math.min(data.length, 8192);
		int control = 0;
		for(int idx=0; idx < n; idx++) {
			int b = data[idx] & 0xff;
			if( b == 0 ) {
				return true;
			}
			if( b < 32 && b != '\n' && b != '\r' && b != '\t' && b != '\f' && b != 27 ) {
				control++;
			}
		}
		return n > 0 && control*10 > n;
	}

	/** 00000000  48 65 6c 6c 6f 0a  ...  |Hello.| */
	static String hexDump(byte[] data) {
		StringBuilder ret = new StringBuilder(data.length*4);
		for(int off=0; off < data.length; off += 16) {
			ret.append(String.format("%08x  ", off));
			StringBuilder ascii = new StringBuilder(16);
			for(int idx=0; idx < 16; idx++) {
				if( off+idx < data.length ) {
					int b = data[off+idx] & 0xff;
					ret.append(String.format("%02x ", b));
					ascii.append(b >= 32 && b < 127 ? (char) b : '.');
				} else {
					ret.append("   ");
				}
				if( idx == 7 ) {
					ret.append(' ');
				}
			}
			ret.append(" |").append(ascii).append("|\n");
		}
		return ret.toString();
	}
}
