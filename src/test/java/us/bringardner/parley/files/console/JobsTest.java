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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.memory.MemoryFileSourceFactory;

/** The screen-free parts: copy, move, search, delete, edit staging and preview. */
public class JobsTest {

	@TempDir
	Path tmp;

	private FileSource local(String rel) throws IOException {
		return FileOps.fromLocalFile(tmp.resolve(rel).toFile());
	}

	private static void write(FileSource f, String text) throws IOException {
		try (OutputStream out = f.getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String read(FileSource f) throws IOException {
		try (InputStream in = f.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** src/a.txt, src/sub/b.txt, src/sub/c.java */
	private FileSource tree() throws IOException {
		FileSource src = local("src");
		assertTrue(src.getChild("sub").mkdirs());
		write(src.getChild("a.txt"), "hello world\n");
		write(src.getChild("sub").getChild("b.txt"), "nothing here\n");
		write(src.getChild("sub").getChild("c.java"), "class C { // hello\n}\n");
		return src;
	}

	@Test
	public void copiesDirectoryTree() throws IOException {
		FileSource src = tree();
		FileSource dest = local("dest");
		assertTrue(dest.mkdirs());
		CopyJob job = new CopyJob(Collections.singletonList(src), dest, false, null);
		job.run();
		assertEquals(3, job.getResult().getFiles());
		assertTrue(job.getResult().getErrors().isEmpty(), job.getResult().getErrors().toString());
		assertEquals("hello world\n", read(dest.getChild("src").getChild("a.txt")));
		assertEquals("class C { // hello\n}\n", read(dest.getChild("src").getChild("sub").getChild("c.java")));
		assertTrue(src.exists());
	}

	@Test
	public void movesWithinOneFileSystem() throws IOException {
		FileSource src = tree();
		FileSource dest = local("dest");
		assertTrue(dest.mkdirs());
		CopyJob job = new CopyJob(Collections.singletonList(src), dest, true, null);
		job.run();
		assertTrue(job.getResult().getErrors().isEmpty(), job.getResult().getErrors().toString());
		assertFalse(src.exists());
		assertEquals("nothing here\n", read(dest.getChild("src").getChild("sub").getChild("b.txt")));
	}

	@Test
	public void copiesAndMovesAcrossFileSystems() throws IOException {
		MemoryFileSourceFactory memory = new MemoryFileSourceFactory();
		memory.connect();
		FileSource mdir = memory.createFileSource("/work");
		assertTrue(mdir.mkdirs());

		FileSource src = tree();
		CopyJob copy = new CopyJob(Collections.singletonList(src), mdir, false, null);
		copy.run();
		assertEquals(3, copy.getResult().getFiles());
		assertEquals("hello world\n", read(mdir.getChild("src").getChild("a.txt")));

		// and back again, as a move: the memory copy goes away
		FileSource back = local("back");
		assertTrue(back.mkdirs());
		CopyJob move = new CopyJob(Collections.singletonList(mdir.getChild("src")), back, true, null);
		move.run();
		assertTrue(move.getResult().getErrors().isEmpty(), move.getResult().getErrors().toString());
		assertFalse(mdir.getChild("src").exists());
		assertEquals("class C { // hello\n}\n", read(back.getChild("src").getChild("sub").getChild("c.java")));
	}

	@Test
	public void conflictsAskAndSkipAll() throws IOException {
		FileSource src = tree();
		FileSource dest = local("dest");
		assertTrue(dest.mkdirs());
		new CopyJob(Collections.singletonList(src), dest, false, null).run();
		write(src.getChild("a.txt"), "changed\n");

		List<String> asked = new ArrayList<>();
		CopyJob again = new CopyJob(Collections.singletonList(src), dest, false, new CopyJob.Listener() {
			@Override
			public CopyJob.Conflict conflict(FileSource from, FileSource existing) {
				asked.add(existing.getName());
				return CopyJob.Conflict.SKIP_ALL;
			}
		});
		again.run();
		assertEquals(1, asked.size());   // asked once, then "all" stands
		assertEquals(3, again.getResult().getSkipped());
		assertEquals("hello world\n", read(dest.getChild("src").getChild("a.txt")));

		CopyJob over = new CopyJob(Collections.singletonList(src.getChild("a.txt")), dest.getChild("src"), false,
				new CopyJob.Listener() {
					@Override
					public CopyJob.Conflict conflict(FileSource from, FileSource existing) {
						return CopyJob.Conflict.OVERWRITE;
					}
				});
		over.run();
		assertEquals("changed\n", read(dest.getChild("src").getChild("a.txt")));
	}

	@Test
	public void refusesCopyIntoItselfOrSameDirectory() throws IOException {
		FileSource src = tree();
		CopyJob into = new CopyJob(Collections.singletonList(src), src.getChild("sub"), false, null);
		into.run();
		assertEquals(1, into.getResult().getErrors().size());

		CopyJob same = new CopyJob(Collections.singletonList(src.getChild("a.txt")), src, false, null);
		same.run();
		assertEquals(1, same.getResult().getErrors().size());
	}

	@Test
	public void searchesByNameAndText() throws IOException {
		FileSource src = tree();
		List<String> byName = new ArrayList<>();
		new SearchJob(src, "*.java", null, true, false, (f, line)->byName.add(f.getName())).run();
		assertEquals(Arrays.asList("c.java"), byName);

		List<String> byText = new ArrayList<>();
		new SearchJob(src, "*", "HELLO", true, false, (f, line)->byText.add(f.getName())).run();
		Collections.sort(byText);
		assertEquals(Arrays.asList("a.txt", "c.java"), byText);

		List<String> caseSensitive = new ArrayList<>();
		new SearchJob(src, null, "HELLO", false, false, (f, line)->caseSensitive.add(f.getName())).run();
		assertTrue(caseSensitive.isEmpty());
	}

	@Test
	public void globs() {
		assertTrue(SearchJob.globToPattern("*.java", false).matcher("A.java").matches());
		assertFalse(SearchJob.globToPattern("*.java", false).matcher("A.javax").matches());
		assertTrue(SearchJob.globToPattern("?.[ch]", false).matcher("x.h").matches());
		assertTrue(SearchJob.globToPattern("a+b(1).txt", false).matcher("a+b(1).txt").matches());
		assertTrue(SearchJob.globToPattern("README*", true).matcher("readme.md").matches());
	}

	@Test
	public void deletesRecursively() throws IOException {
		FileSource src = tree();
		assertEquals(5, FileOps.deleteRecursive(src, null));
		assertFalse(src.exists());
	}

	@Test
	public void editStagesRemoteFilesAndWritesBack() throws IOException {
		MemoryFileSourceFactory memory = new MemoryFileSourceFactory();
		memory.connect();
		FileSource remote = memory.createFileSource("/notes.txt");
		write(remote, "one\n");

		try (EditSession session = EditSession.open(remote)) {
			assertTrue(session.isRemote());
			File local = session.getLocalFile();
			assertEquals("notes.txt", local.getName());
			assertFalse(session.isChanged());
			Files.write(local.toPath(), "one\ntwo\n".getBytes(StandardCharsets.UTF_8));
			assertTrue(session.isChanged());
			assertFalse(session.isRemoteChanged());
			session.writeBack();
		}
		assertEquals("one\ntwo\n", read(remote));
	}

	@Test
	public void editsLocalFilesInPlace() throws IOException {
		FileSource f = local("x.txt");
		write(f, "x");
		try (EditSession session = EditSession.open(f)) {
			assertFalse(session.isRemote());
			assertEquals(tmp.resolve("x.txt").toFile().getCanonicalFile(), session.getLocalFile().getCanonicalFile());
		}
		assertTrue(f.exists());
	}

	@Test
	public void previewsTextAndBinary() throws IOException {
		FileSource text = local("t.txt");
		write(text, "line\tone\n");
		Preview p = Preview.of(text);
		assertFalse(p.isBinary());
		assertEquals("line    one\n", p.getText());

		FileSource bin = local("b.bin");
		try (OutputStream out = bin.getOutputStream()) {
			out.write(new byte[] {0, 1, 2, 'A'});
		}
		Preview b = Preview.of(bin);
		assertTrue(b.isBinary());
		assertTrue(b.getText().startsWith("00000000  00 01 02 41"), b.getText());

		Preview cut = Preview.of(text, 4);
		assertTrue(cut.isTruncated());
	}

	@Test
	public void formats() {
		assertEquals("512 B", FileOps.formatSize(512));
		assertEquals("1.5 K", FileOps.formatSize(1536));
		assertEquals("20 M", FileOps.formatSize(20L*1024*1024));
		assertEquals("", FileOps.formatDate(0));
	}

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
		String narrow = ConsoleBrowser.keyBar(40);
		assertTrue(narrow.length() <= 40, narrow);
		assertTrue(narrow.endsWith("? help  q quit"), narrow);
		String wide = ConsoleBrowser.keyBar(500);
		assertTrue(wide.startsWith("Tab pane"), wide);
		assertTrue(wide.contains("h hidden"), wide);
	}

	@Test
	public void listsInstalledFileSystems() throws IOException {
		List<String> types = Connections.types();
		assertEquals("fileproxy", types.get(0));
		assertTrue(types.contains("memory"), types.toString());
		assertTrue(types.contains("sftp"), types.toString());
		assertNotNull(Connections.create("memory"));
		assertNull(us.bringardner.parley.files.FileSourceFactory.getFileSourceFactory("nope"));
	}
}
