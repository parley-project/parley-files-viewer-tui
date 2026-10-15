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
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceCopy;
import us.bringardner.parley.files.StreamOptions;
import us.bringardner.parley.files.browse.DirectoryListing;

/**
 * Copies (or moves) files and directories into a directory, on any pair of file systems, and
 * reports what it's doing to a {@link Listener}. Runs on whatever thread calls {@link #run()};
 * the console runs it on a worker and shows the progress in a dialog. Nothing here touches the
 * screen, so it can be tested on its own.
 * <p>
 * A move inside one file system is a rename; across file systems it's a copy followed by a
 * delete of what was copied without error.
 */
public final class CopyJob implements Runnable {

	/** What to do when the target already exists. */
	public enum Conflict {
		OVERWRITE,
		SKIP,
		OVERWRITE_ALL,
		SKIP_ALL,
		CANCEL
	}

	/** Called on the thread running the job. */
	public interface Listener {
		/** Before anything is copied, once the sources have been counted. */
		default void started(int files, long bytes) {
		}

		/** A file is about to be copied. */
		default void file(FileSource from, FileSource to) {
		}

		/** Bytes and files done so far; called often, but no more than about ten times a second. */
		default void progress(long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
		}

		/** The target exists; the answer decides. The default skips it. */
		default Conflict conflict(FileSource from, FileSource existing) {
			return Conflict.SKIP;
		}

		/** Something couldn't be copied; the job carries on with the rest. */
		default void error(FileSource from, IOException e) {
		}

		/** The job is over, whether it finished, failed or was canceled. */
		default void finished(Result result) {
		}
	}

	/** What a job did. */
	public static final class Result {
		private int files;
		private long bytes;
		private int skipped;
		private boolean canceled;
		private final List<String> errors = new ArrayList<>();

		public int getFiles() {
			return files;
		}

		public long getBytes() {
			return bytes;
		}

		public int getSkipped() {
			return skipped;
		}

		public boolean isCanceled() {
			return canceled;
		}

		public List<String> getErrors() {
			return Collections.unmodifiableList(errors);
		}

		@Override
		public String toString() {
			return files+" file(s), "+FileOps.formatSize(bytes)
					+(skipped > 0 ? ", "+skipped+" skipped" : "")
					+(errors.isEmpty() ? "" : ", "+errors.size()+" error(s)")
					+(canceled ? " (canceled)" : "");
		}
	}

	private static final long REPORT_EVERY_MS = 100;

	private final List<FileSource> sources;
	private final FileSource destDir;
	private final boolean move;
	private final Listener listener;
	private final Result result = new Result();
	private volatile boolean canceled;
	private Conflict standing;
	private int filesTotal;
	private long bytesTotal;
	private long lastReport;

	public CopyJob(List<FileSource> sources, FileSource destDir, boolean move, Listener listener) {
		this.sources = new ArrayList<>(sources);
		this.destDir = destDir;
		this.move = move;
		this.listener = listener == null ? new Listener() { } : listener;
	}

	public boolean isMove() {
		return move;
	}

	/** Stop as soon as possible; the file being copied is left partly written and then deleted. */
	public void cancel() {
		canceled = true;
	}

	public boolean isCanceled() {
		return canceled;
	}

	public Result getResult() {
		return result;
	}

	@Override
	public void run() {
		try {
			count();
			listener.started(filesTotal, bytesTotal);
			for(FileSource src : sources) {
				if( canceled ) {
					break;
				}
				try {
					transfer(src);
				} catch (IOException e) {
					fail(src, e);
				}
			}
		} catch (IOException e) {
			result.errors.add(e.getMessage());
			listener.error(null, e);
		} finally {
			result.canceled = canceled;
			listener.finished(result);
		}
	}

	private void count() throws IOException {
		for(FileSource src : sources) {
			count(src);
		}
	}

	private void count(FileSource file) throws IOException {
		if( canceled ) {
			return;
		}
		if( file.isDirectory()) {
			FileSource[] kids = file.listFiles();
			if( kids != null ) {
				for(FileSource kid : kids) {
					count(kid);
				}
			}
		} else {
			filesTotal++;
			bytesTotal += file.length();
		}
	}

	private void transfer(FileSource src) throws IOException {
		FileSource parent = src.getParentFile();
		if( parent != null && DirectoryListing.samePath(parent, destDir)) {
			throw new IOException(src.getName()+" is already in "+destDir.getAbsolutePath());
		}
		if( src.isDirectory() && (DirectoryListing.samePath(src, destDir) || isInside(destDir, src))) {
			throw new IOException("Can't copy "+src.getAbsolutePath()+" into itself");
		}
		FileSource target = destDir.getChild(src.getName());
		if( move && !target.exists() && src.getFileSourceFactory().isSameFileSystem(destDir.getFileSourceFactory())) {
			int files = src.isDirectory() ? -1 : 1;
			long bytes = src.isDirectory() ? 0 : src.length();
			if( src.renameTo(target)) {
				if( files == 1 ) {
					result.files++;
					result.bytes += bytes;
				} else {
					result.files += countFiles(target);
				}
				report(true);
				return;
			}
			// rename refused (another device, say): fall back to copy and delete
		}
		if( copy(src, target) && move && !canceled ) {
			FileOps.deleteRecursive(src, ()->canceled);
		}
	}

	/** @return true if everything under src was copied (nothing skipped or failed) */
	private boolean copy(FileSource src, FileSource target) throws IOException {
		if( canceled ) {
			return false;
		}
		if( src.isDirectory()) {
			if( !target.isDirectory() && !target.mkdirs()) {
				throw new IOException("Can't create directory "+target.getAbsolutePath());
			}
			boolean all = true;
			FileSource[] kids = src.listFiles();
			if( kids != null ) {
				for(FileSource kid : kids) {
					try {
						all &= copy(kid, target.getChild(kid.getName()));
					} catch (IOException e) {
						fail(kid, e);
						all = false;
					}
				}
			}
			return all && !canceled;
		}
		if( target.exists()) {
			if( target.isDirectory()) {
				throw new IOException(target.getAbsolutePath()+" is a directory");
			}
			Conflict answer = standing != null ? standing : listener.conflict(src, target);
			if( answer == Conflict.OVERWRITE_ALL || answer == Conflict.SKIP_ALL ) {
				standing = answer;
			}
			if( answer == Conflict.CANCEL ) {
				cancel();
				return false;
			}
			if( answer == Conflict.SKIP || answer == Conflict.SKIP_ALL ) {
				result.skipped++;
				bytesTotal -= src.length();
				filesTotal--;
				report(true);
				return false;
			}
		}
		listener.file(src, target);
		copyFile(src, target);
		result.files++;
		report(true);
		return true;
	}

	private void copyFile(FileSource from, FileSource to) throws IOException {
		int block = FileSourceCopy.blockSizeFor(from, to);
		StreamOptions options = FileSourceCopy.optionsFor(block);
		boolean done = false;
		try (InputStream in = from.getInputStream(options); OutputStream out = to.getOutputStream(false, options)) {
			byte[] buffer = new byte[block];
			int n;
			while( (n = in.read(buffer)) >= 0 ) {
				if( canceled ) {
					break;
				}
				if( n > 0 ) {
					out.write(buffer, 0, n);
					result.bytes += n;
					report(false);
				}
			}
			done = !canceled;
		} finally {
			if( !done ) {
				try {
					to.delete();
				} catch (IOException e) {
					// leave it; the copy already failed
				}
			}
		}
		try {
			long modified = from.lastModified();
			if( modified > 0 ) {
				to.setLastModified(modified);
			}
		} catch (IOException | RuntimeException e) {
			// not every file system can set it
		}
	}

	private void fail(FileSource src, IOException e) {
		result.errors.add((src == null ? "" : src.getAbsolutePath()+": ")+e.getMessage());
		listener.error(src, e);
	}

	private void report(boolean force) {
		long now = System.currentTimeMillis();
		if( force || now-lastReport >= REPORT_EVERY_MS ) {
			lastReport = now;
			listener.progress(result.bytes, bytesTotal, result.files, filesTotal);
		}
	}

	private static boolean isInside(FileSource child, FileSource dir) throws IOException {
		for(FileSource p = child.getParentFile(); p != null; p = p.getParentFile()) {
			if( DirectoryListing.samePath(p, dir)) {
				return true;
			}
		}
		return false;
	}

	private static int countFiles(FileSource dir) throws IOException {
		int ret = 0;
		FileSource[] kids = dir.listFiles();
		if( kids != null ) {
			for(FileSource kid : kids) {
				ret += kid.isDirectory() ? countFiles(kid) : 1;
			}
		}
		return ret;
	}
}
