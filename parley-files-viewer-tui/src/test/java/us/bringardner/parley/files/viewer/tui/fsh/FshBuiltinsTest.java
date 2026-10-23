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
package us.bringardner.parley.files.viewer.tui.fsh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs fsh (fsh -c SCRIPT) in its own process with this plugin on its class path, as fsh
 * finds it in a plugin folder, and checks what the plugin's commands and browser do.
 */
public class FshBuiltinsTest {

	@TempDir
	Path tmp;

	@BeforeEach
	public void files() throws IOException {
		Files.createDirectories(tmp.resolve("d/sub"));
		Files.write(tmp.resolve("d/a.txt"), "hello world\n".getBytes(StandardCharsets.UTF_8));
		Files.write(tmp.resolve("d/sub/b.md"), "Hello again\n".getBytes(StandardCharsets.UTF_8));
		Files.write(tmp.resolve("d/bin.dat"), new byte[] {0, 1, 'A', 'B'});
		Files.write(tmp.resolve("d/.hidden.txt"), "hello\n".getBytes(StandardCharsets.UTF_8));
	}

	/** stdout and stderr of fsh running script in tmp */
	private String fsh(String script) throws Exception {
		File editor = tmp.resolve("editor.sh").toFile();
		Files.write(editor.toPath(), "#!/bin/sh\necho edited >> \"$1\"\n".getBytes(StandardCharsets.UTF_8));
		editor.setExecutable(true);

		List<String> cmd = new ArrayList<>(Arrays.asList(
				new File(System.getProperty("java.home"), "bin/java").getPath(),
				"-cp", System.getProperty("java.class.path"),
				// (no plugin folders: only what is on the class path)
				"-Dparley.plugins.path="+tmp.resolve("no-plugins"),
				"us.bringardner.fsh.Console", "-c", script));
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(tmp.toFile()).redirectErrorStream(true);
		pb.environment().put("EDITOR", editor.getAbsolutePath());
		pb.environment().remove("VISUAL");
		pb.environment().remove("JAVA_TOOL_OPTIONS");
		Process p = pb.start();
		p.getOutputStream().close();
		byte[] out = p.getInputStream().readAllBytes();
		assertTrue(p.waitFor(60, TimeUnit.SECONDS));
		return new String(out, StandardCharsets.UTF_8).replace("\r", "");
	}

	@Test
	public void searchesByNameAndText() throws Exception {
		assertEquals("d/a.txt\nrc=0\n", fsh("search -n '*.txt' d; echo rc=$?"));
		assertEquals("d/a.txt: hello world\nd/sub/b.md: Hello again\n", fsh("search -i -t hello d | sort"));
		assertEquals("d/a.txt\nd/sub/b.md\n", fsh("search -il -t hello d | sort"));
		assertEquals("d/.hidden.txt\nd/a.txt\n", fsh("search -a -n '*.txt' d | sort"));
		assertEquals("rc=1\n", fsh("search -n nope d; echo rc=$?"));
		assertEquals("./a.txt\n", fsh("cd d; search -n a.txt"));
	}

	@Test
	public void viewPrintsWhenNotATerminal() throws Exception {
		assertEquals("hello world\n", fsh("view d/a.txt | cat"));
		assertTrue(fsh("view -c d/bin.dat").startsWith("00000000  00 01 41 42"));
		assertTrue(fsh("view -cx d/a.txt").startsWith("00000000  68 65 6c 6c 6f"));
		assertTrue(fsh("view -c d/nope; echo rc=$?").contains("No such file"));
	}

	@Test
	public void editsLocalAndMountedFiles() throws Exception {
		fsh("edit d/a.txt");
		assertEquals("hello world\nedited\n", new String(Files.readAllBytes(tmp.resolve("d/a.txt")), StandardCharsets.UTF_8));

		// a file on a connected file system is staged, edited and copied back; the local one is untouched
		String out = fsh("connect memory /mem; mkdir /mem/w; cp d/sub/b.md /mem/w/; edit /mem/w/b.md; view -c /mem/w/b.md");
		assertTrue(out.contains("edit: saved /mem/w/b.md to memory"), out);
		assertTrue(out.endsWith("Hello again\nedited\n"), out);
		assertEquals("Hello again\n", new String(Files.readAllBytes(tmp.resolve("d/sub/b.md")), StandardCharsets.UTF_8));
	}

	@Test
	public void searchesMountedFileSystems() throws Exception {
		String out = fsh("connect memory /mem >/dev/null; mkdir /mem/x; cp d/a.txt /mem/x/; search -t world /mem");
		assertEquals("/mem/x/a.txt: hello world\n", out);
	}

	@Test
	public void browseNeedsTheTerminal() throws Exception {
		assertTrue(fsh("browse | cat; echo rc=$?").contains("browse: no browser can run here"));
		assertEquals("  terminal - the full-screen browser on Parley's own terminal code (cannot run here)\n",
				fsh("browse --list | cat"));
	}

	@Test
	public void builtinsHaveHelp() throws Exception {
		String out = fsh("for c in view edit search; do type $c; done; help view; plugins");
		assertTrue(out.contains("view is a shell builtin"), out);
		assertTrue(out.contains("search is a shell builtin"), out);
		assertTrue(out.contains("-x   hex dump even if the file looks like text"), out);
		assertTrue(out.contains("commands:\n  edit (classes)\n  search (classes)\n  view (classes)\n"), out);
		assertTrue(out.contains("browsers:\n  terminal (classes)\n"), out);
	}
}
