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
package us.bringardner.parley.files.viewer.tui.termui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiConsumer;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.viewer.tui.Connections;
import us.bringardner.parley.files.viewer.tui.FileOps;
import us.bringardner.parley.term.screen.Dialog;
import us.bringardner.parley.term.screen.Field;
import us.bringardner.parley.term.tty.Key;

/**
 * The connect dialog, as ConnectDialog is in the Lanterna version: a form built from the
 * factory's ConnectionSettings, so it works for every file system without knowing any of them.
 * Only the settings that apply are shown (visibleWhen), the advanced ones are behind More...,
 * the line under the form describes the field being edited, a local file can be typed, picked
 * from the files where it is (Enter) or found with Browse..., and Start in says which folder to
 * open once connected. The values are checked with the factory before the form closes.
 */
final class ConnectForm {

	static final String START_IN_HELP = "The folder to open once connected; empty: where the file system starts";
	static final String CONNECT_AT_HELP = "Where it is connected in the shell's file tree; it stays connected when you quit (D disconnects)";

	/** what the form gives once its values are valid */
	interface Done {
		/** @param connectAt where to connect it in the host's tree, or null (no host) */
		void ok(Properties values, String startIn, String connectAt);
	}

	private ConnectForm() {
	}

	/**
	 * @param ok given the values and the folder to start in, once they're valid
	 */
	static Dialog.Form create(FileSourceFactory factory, Dialog.Stack dialogs, BiConsumer<Properties, String> ok) {
		return create(factory, dialogs, null, (values, startIn, connectAt)->ok.accept(values, startIn));
	}

	/**
	 * @param taken  the paths the host's connections are at, or null if there's no host (then
	 *               there's no Connect at)
	 * @param ok     given the values, the folder to start in and where to connect it, once valid
	 */
	static Dialog.Form create(FileSourceFactory factory, Dialog.Stack dialogs, java.util.Set<String> taken, Done ok) {
		Dialog.Form form = new Dialog.Form("Connect: "+Connections.label(factory.getTypeId()));
		form.width = 76;
		form.footer = "Enter: connect   Esc: cancel   Tab: next field";
		List<ConnectionSetting> settings = Connections.formSettings(factory);
		Properties initial = Connections.initialValues(factory);
		Map<String, Field> fields = new LinkedHashMap<>();
		boolean[] advanced = {false};
		boolean anyAdvanced = false;
		for(boolean adv : new boolean[] {false, true}) {
			boolean header = false;
			for(ConnectionSetting s : settings) {
				if( s.advanced() != adv ) {
					continue;
				}
				anyAdvanced |= adv;
				String value = initial.getProperty(s.key(), s.defaultValue() == null ? "" : s.defaultValue());
				Field f = fieldFor(s, value, dialogs);
				f.help = s.description();
				fields.put(s.key(), f);
				if( adv && !header ) {
					form.add("Advanced:", new Field.Label(), null, ()->advanced[0]);
					header = true;
				}
				Field extra = s.kind() == ConnectionSetting.Kind.LOCAL_FILE ? browse(s, (FileField) f, dialogs, form) : null;
				form.add(s.label()+(s.required() ? " *" : ""), f, extra,
						()->(!adv || advanced[0]) && ConnectionSettings.isVisible(s, settings, values(fields)));
			}
		}
		if( fields.isEmpty()) {
			form.add("Nothing to set up", new Field.Label());
		}
		Field.Text startIn = new Field.Text("", false, false);
		startIn.help = START_IN_HELP;
		form.add("Start in", startIn);
		Field.Text connectAt = null;
		String[] suggested = {null};
		if( taken != null ) {
			suggested[0] = us.bringardner.parley.files.viewer.tui.HostConnections.suggestPath(factory.getTypeId(), initial, taken);
			connectAt = new Field.Text(suggested[0], false, false);
			connectAt.help = CONNECT_AT_HELP;
			form.add("Connect at", connectAt);
		}
		Field.Text at = connectAt;

		form.buttons.add(new Field.Button("Connect", form::submit));
		form.buttons.add(new Field.Button("Cancel", ()->form.cancel()));
		if( anyAdvanced ) {
			form.buttons.add(new Field.Button("More... / Fewer", ()->advanced[0] = !advanced[0]));
		}
		form.submit = f->{
			Properties p = values(fields);
			List<String> problems = factory.validateConnection(p);
			if( !problems.isEmpty()) {
				return String.join("\n", problems);
			}
			String where = null;
			if( at != null ) {
				where = at.value().trim();
				if( where.equals(suggested[0])) {
					// (not changed: what the values now suggest, the host typed in included)
					where = us.bringardner.parley.files.viewer.tui.HostConnections.suggestPath(factory.getTypeId(), p, taken);
				}
				while( where.length() > 1 && where.endsWith("/")) {
					where = where.substring(0, where.length()-1);
				}
				String problem = us.bringardner.parley.files.viewer.tui.HostConnections.checkPath(where, taken);
				if( problem != null ) {
					return problem;
				}
			}
			ok.ok(p, startIn.value().trim(), where);
			return null;
		};
		return form;
	}

	/** Every setting's value (those that don't apply too: the factory ignores them). */
	static Properties values(Map<String, Field> fields) {
		Properties ret = new Properties();
		for(Map.Entry<String, Field> e : fields.entrySet()) {
			String v = e.getValue().value();
			ret.setProperty(e.getKey(), v == null ? "" : v);
		}
		return ret;
	}

	private static Field fieldFor(ConnectionSetting s, String value, Dialog.Stack dialogs) {
		switch (s.kind()) {
			case BOOLEAN:
				return new Field.Check(Boolean.parseBoolean(value), null);
			case CHOICE:
				return new Field.Choice(s.choices(), value);
			case SECRET:
				return new Field.Text(value, true, false);
			case MULTILINE_SECRET:
				return new Field.MultiLine(value);
			case INTEGER:
				return new Field.Text(value, false, true);
			case LOCAL_FILE:
				return new FileField(FileOps.abbreviateHome(value), dialogs);
			default:
				return new Field.Text(value, false, false);
		}
	}

	private static Field.Button browse(ConnectionSetting s, FileField field, Dialog.Stack dialogs, Dialog.Form form) {
		Field.Button b = new Field.Button("Browse...", ()->dialogs.open(new Dialog.Picker(s.label(),
				startFor(field.value()), true, chosen->{
					field.setValue(FileOps.abbreviateHome(chosen.getAbsolutePath()));
					form.setFocus(field);
				})));
		b.help = "Choose the file from your folders";
		return b;
	}

	/**
	 * A local file: typed (~ is the home folder), or Enter for a list of the files where it is
	 * (else ~/.ssh, else home), as the Lanterna version's drop-down. The factory gets the full path.
	 */
	static final class FileField extends Field.Text {
		private final Dialog.Stack dialogs;

		FileField(String value, Dialog.Stack dialogs) {
			super(value, false, false);
			this.dialogs = dialogs;
		}

		@Override
		public boolean submitsOnEnter() {
			return false;
		}

		@Override
		public boolean key(Key k) {
			if( k.is(Key.Code.ENTER)) {
				File folder = startFor(super.value());
				if( !folder.isDirectory()) {
					folder = folder.getParentFile();
				}
				List<String> choices = fileChoices(folder);
				dialogs.open(new Dialog.Choose("Files in "+FileOps.abbreviateHome(folder.getAbsolutePath()), null,
						choices, 64, idx->setValue(choices.get(idx)), null));
				return true;
			}
			return super.key(k);
		}

		@Override
		public String value() {
			return FileOps.expandHome(super.value().trim());
		}
	}

	/** The files in folder, by name, leaving out hidden ones, at most 100; ~ for home. */
	static List<String> fileChoices(File folder) {
		List<String> ret = new ArrayList<>();
		File[] files = folder == null ? null : folder.listFiles();
		if( files == null ) {
			return ret;
		}
		Arrays.sort(files, (a, b)->a.getName().compareToIgnoreCase(b.getName()));
		for(File f : files) {
			if( f.isFile() && !f.isHidden() && !f.getName().startsWith(".")) {
				ret.add(FileOps.abbreviateHome(f.getAbsolutePath()));
				if( ret.size() == 100 ) {
					break;
				}
			}
		}
		return ret;
	}

	static File startFor(String value) {
		if( value != null && !value.trim().isEmpty()) {
			File f = new File(FileOps.expandHome(value.trim()));
			if( f.exists()) {
				return f;
			}
			if( f.getParentFile() != null && f.getParentFile().isDirectory()) {
				return f.getParentFile();
			}
		}
		File home = new File(System.getProperty("user.home"));
		File ssh = new File(home, ".ssh");
		return ssh.isDirectory() ? ssh : home;
	}
}
