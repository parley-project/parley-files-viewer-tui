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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.FileDialogBuilder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * The TUI version of the viewer's connection dialog: a form built from the factory's
 * {@link ConnectionSetting}s (text, secret, number, yes/no, choice, local file), so it works for
 * every file system without knowing about any of them.
 * <p>
 * It shows only the settings that apply (a setting {@link ConnectionSetting#visibleWhen visible
 * when} another has a value appears and disappears as that one changes, so "Log in with" shows
 * the password or the key file, not both); the advanced settings are behind More...; the line
 * under the form describes the field being edited; a local file can be chosen from a list
 * (Browse...); and Start in says which folder to open once connected.
 */
public final class ConnectDialog extends BasicWindow {

	/** What the form gives: the factory's settings, and the folder to open first. */
	public static final class Answer {
		/** The values of the settings, for the factory. */
		public final Properties values;
		/** The folder to open once connected; empty to start where the file system does. */
		public final String startIn;

		Answer(Properties values, String startIn) {
			this.values = values;
			this.startIn = startIn;
		}
	}

	static final String START_IN_HELP = "The folder to open once connected; empty: where the file system starts";
	private static final int HELP_WIDTH = 60;

	private final WindowBasedTextGUI gui;
	private final List<ConnectionSetting> settings;
	private final Map<String, Supplier<String>> values = new LinkedHashMap<>();
	/** setting key -> its label, editor and the column after it (Browse... or nothing) */
	private final Map<String, Component[]> rows = new LinkedHashMap<>();
	private final Map<Interactable, ConnectionSetting> owners = new HashMap<>();
	private final Panel form = new Panel(new GridLayout(3));
	private final Label problems = new Label("");
	private final Label help = new Label("");
	private final TextBox startIn = new SubmitBox("");
	private final Component[] startInRow;
	private final Button more;
	private boolean showAdvanced;
	private boolean ok;

	private ConnectDialog(WindowBasedTextGUI gui, FileSourceFactory factory) {
		super("Connect: "+Connections.label(factory.getTypeId()));
		this.gui = gui;
		setHints(Arrays.asList(Window.Hint.CENTERED, Window.Hint.MODAL));

		Properties initial = Connections.initialValues(factory);
		settings = Connections.formSettings(factory);
		boolean anyAdvanced = false;
		for(ConnectionSetting s : settings) {
			String value = initial.getProperty(s.key(), s.defaultValue() == null ? "" : s.defaultValue());
			Label label = new Label(s.label()+(s.required() ? " *" : ""));
			Component editor = editorFor(s, value);
			Component extra = s.kind() == ConnectionSetting.Kind.LOCAL_FILE ? browseButton(s) : new EmptySpace();
			rows.put(s.key(), new Component[] {label, editor, extra});
			anyAdvanced |= s.advanced();
		}
		startInRow = new Component[] {new Label("Start in"), startIn, new EmptySpace()};

		more = new Button("More...", ()->{
			showAdvanced = !showAdvanced;
			refresh();
		});
		Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
		buttons.addComponent(new Button("Connect", ()->{
			ok = true;
			close();
		}));
		buttons.addComponent(new Button("Cancel", this::close));
		if( anyAdvanced ) {
			buttons.addComponent(more);
		}

		// the rows first, so the window starts in the first field (not on Connect)
		refresh();
		Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
		root.addComponent(form);
		root.addComponent(problems);
		root.addComponent(help);
		root.addComponent(new Label("Enter: connect   Esc: cancel   Tab: next field"));
		root.addComponent(buttons);
		setComponent(root);
		updateHelp();
	}

	/** The rows of the settings that apply now, then Start in. */
	private void refresh() {
		Interactable focused = getFocusedInteractable();
		form.removeAllComponents();
		Properties current = collect();
		boolean any = false;
		boolean advancedHeader = false;
		for(boolean advanced : new boolean[] {false, true}) {
			if( advanced && !showAdvanced ) {
				break;
			}
			for(ConnectionSetting s : settings) {
				if( s.advanced() != advanced || !ConnectionSettings.isVisible(s, settings, current)) {
					continue;
				}
				if( advanced && !advancedHeader ) {
					form.addComponent(new Label("Advanced:"));
					form.addComponent(new EmptySpace());
					form.addComponent(new EmptySpace());
					advancedHeader = true;
				}
				for(Component c : rows.get(s.key())) {
					form.addComponent(c);
				}
				any = true;
			}
		}
		if( !any ) {
			form.addComponent(new Label("Nothing to set up"));
			form.addComponent(new EmptySpace());
			form.addComponent(new EmptySpace());
		}
		for(Component c : startInRow) {
			form.addComponent(c);
		}
		more.setLabel(showAdvanced ? "Fewer" : "More...");
		if( focused instanceof Component && ((Component) focused).getParent() != null ) {
			setFocusedInteractable(focused);
		} else if( focused != null ) {
			// the field being edited no longer applies: the first one that does
			setFocusedInteractable(form.nextFocus(null));
		}
		updateHelp();
	}

	/** The description of the field being edited, wrapped, always two lines so the window keeps its size. */
	private void updateHelp() {
		Interactable f = getFocusedInteractable();
		String text = "";
		ConnectionSetting s = owners.get(f);
		if( s != null && s.description() != null ) {
			text = s.description();
			if( s.kind() == ConnectionSetting.Kind.LOCAL_FILE && f instanceof ComboBox ) {
				text += ". Type a path, or Enter for a list of files";
			}
		} else if( f == startIn ) {
			text = START_IN_HELP;
		}
		help.setText(wrap(text, HELP_WIDTH, 2));
	}

	static String wrap(String text, int width, int lines) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for(String word : text.trim().split("\\s+")) {
			if( word.isEmpty()) {
				continue;
			}
			if( line.length() > 0 && line.length()+1+word.length() > width ) {
				out.add(line.toString());
				line.setLength(0);
			}
			if( line.length() > 0 ) {
				line.append(' ');
			}
			line.append(word);
		}
		if( line.length() > 0 ) {
			out.add(line.toString());
		}
		while( out.size() > lines ) {
			String last = out.remove(out.size()-1);
			String prev = out.remove(out.size()-1);
			out.add(prev+" "+last);
		}
		if( out.size() == lines && out.get(lines-1).length() > width ) {
			out.set(lines-1, out.get(lines-1).substring(0, width-3)+"...");
		}
		while( out.size() < lines ) {
			out.add("");
		}
		return String.join("\n", out);
	}

	private Component editorFor(ConnectionSetting s, String value) {
		switch (s.kind()) {
			case BOOLEAN: {
				CheckBox box = new CheckBox();
				box.setChecked(Boolean.parseBoolean(value));
				values.put(s.key(), ()->Boolean.toString(box.isChecked()));
				box.addListener(checked->refresh());
				owners.put(box, s);
				return box;
			}
			case CHOICE: {
				ComboBox<String> box = new ComboBox<>(s.choices());
				int idx = s.choices().indexOf(value);
				if( idx >= 0 ) {
					box.setSelectedIndex(idx);
				}
				values.put(s.key(), ()->box.getSelectedItem());
				box.addListener((selected, previous, byUser)->refresh());
				owners.put(box, s);
				return box;
			}
			case SECRET: {
				TextBox box = new SubmitBox(value);
				box.setMask('*');
				values.put(s.key(), box::getText);
				owners.put(box, s);
				return box;
			}
			case MULTILINE_SECRET: {
				TextBox box = new TextBox(new TerminalSize(32, 4), value, TextBox.Style.MULTI_LINE);
				values.put(s.key(), box::getText);
				owners.put(box, s);
				return box;
			}
			case LOCAL_FILE: {
				// a drop-down of the files where this one is (else ~/.ssh, else home) that can be
				// typed in too; Browse... for anywhere else
				ComboBox<String> box = new ComboBox<>(FileOps.abbreviateHome(value), fileChoices(startFor(value)));
				box.setReadOnly(false);
				box.setDropDownNumberOfRows(10);
				// a fixed width: the longest path in the list would make the form as wide
				box.setPreferredSize(new TerminalSize(36, 1));
				// shown with ~ for the home directory; the factory gets the full path
				values.put(s.key(), ()->FileOps.expandHome(box.getText().trim()));
				owners.put(box, s);
				return box;
			}
			default: {
				TextBox box = new SubmitBox(value);
				if( s.kind() == ConnectionSetting.Kind.INTEGER ) {
					box.setValidationPattern(java.util.regex.Pattern.compile("-?[0-9]*"));
				}
				values.put(s.key(), box::getText);
				owners.put(box, s);
				return box;
			}
		}
	}

	/** Browse...: choose the file from a list, starting at the one in the field, else ~/.ssh, else home. */
	private Button browseButton(ConnectionSetting s) {
		Button b = new Button("Browse...", ()->{
			Component[] row = rows.get(s.key());
			@SuppressWarnings("unchecked")
			ComboBox<String> field = (ComboBox<String>) row[1];
			FileDialogBuilder builder = new FileDialogBuilder()
					.setTitle(s.label())
					.setActionLabel("Choose")
					.setSelectedFile(startFor(field.getText()));
			builder.setShowHiddenDirectories(true);
			File chosen = builder.build().showDialog(gui);
			if( chosen != null ) {
				choose(field, FileOps.abbreviateHome(chosen.getAbsolutePath()));
				setFocusedInteractable(field);
			}
		});
		owners.put(b, s);
		return b;
	}

	/** Put path in a file drop-down: chosen, and in its list if it wasn't there. */
	static void choose(ComboBox<String> box, String path) {
		for(int i=0; i < box.getItemCount(); i++) {
			if( box.getItem(i).equals(path)) {
				box.setSelectedIndex(i);
				return;
			}
		}
		box.addItem(0, path);
		box.setSelectedIndex(0);
	}

	/**
	 * The drop-down's list: the files in dir (or in the folder of the file dir names), by name,
	 * leaving out hidden ones and at most 100; ~ for the home directory.
	 */
	static List<String> fileChoices(File dir) {
		File folder = dir.isDirectory() ? dir : dir.getParentFile();
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

	/** A one-line field where Enter connects, as it would in any form. */
	private final class SubmitBox extends TextBox {
		SubmitBox(String value) {
			super(new TerminalSize(32, 1), value);
		}

		@Override
		public Result handleKeyStroke(KeyStroke key) {
			if( key.getKeyType() == KeyType.Enter ) {
				ok = true;
				close();
				return Result.HANDLED;
			}
			return super.handleKeyStroke(key);
		}
	}

	@Override
	public boolean handleInput(KeyStroke key) {
		if( key.getKeyType() == KeyType.Escape ) {
			ok = false;
			close();
			return true;
		}
		boolean ret = super.handleInput(key);
		updateHelp();
		return ret;
	}

	/** The values of every setting (those that don't apply too: the factory ignores them). */
	private Properties collect() {
		Properties ret = new Properties();
		for(Map.Entry<String, Supplier<String>> e : values.entrySet()) {
			String v = e.getValue().get();
			ret.setProperty(e.getKey(), v == null ? "" : v);
		}
		return ret;
	}

	/**
	 * Ask for the settings, checking them until they're valid or the user cancels.
	 *
	 * @return the values and the folder to start in, or null if canceled
	 */
	public static Answer show(WindowBasedTextGUI gui, FileSourceFactory factory) {
		ConnectDialog dialog = new ConnectDialog(gui, factory);
		while( true ) {
			dialog.ok = false;
			gui.addWindowAndWait(dialog);
			if( !dialog.ok ) {
				return null;
			}
			Properties p = dialog.collect();
			List<String> errors = factory.validateConnection(p);
			if( errors.isEmpty()) {
				return new Answer(p, dialog.startIn.getText().trim());
			}
			dialog.problems.setText(String.join("\n", errors));
		}
	}
}
