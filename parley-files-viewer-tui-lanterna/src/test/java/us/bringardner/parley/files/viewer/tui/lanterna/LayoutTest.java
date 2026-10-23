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
package us.bringardner.parley.files.viewer.tui.lanterna;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The Lanterna browser's header and key bar fit the width they are given. */
public class LayoutTest {

	@Test
	public void headersFitTheirWidth() {
		assertEquals("> [Local] /a/b", FilePane.fitHeader("> [Local] ", "/a/b", "", 40));
		String h = FilePane.fitHeader("> [Local] ", "/var/folders/7x/qq1k/T/junit123/left", " +hidden", 30);
		assertEquals(30, h.length());
		assertEquals("> [Local] ...nit123/left +hidden", FilePane.fitHeader("> [Local] ", "/var/folders/7x/qq1k/T/junit123/left", " +hidden", 32));
		assertEquals("> [Lo", FilePane.fitHeader("> [Local] ", "/x/y/z", "", 5));
	}

	@Test
	public void keyBarFitsTheWidth() {
		String narrow = TuiBrowser.keyBar(40);
		assertTrue(narrow.length() <= 40, narrow);
		assertTrue(narrow.endsWith("? help  q quit"), narrow);
		String wide = TuiBrowser.keyBar(500);
		assertTrue(wide.startsWith("Tab pane"), wide);
		assertTrue(wide.contains("h hidden"), wide);
	}
}
