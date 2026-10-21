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

import java.util.Arrays;
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
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSourceFactory;

/**
 * The TUI version of the viewer's connection dialog: a form built from the factory's
 * {@link ConnectionSetting}s (text, secret, number, yes/no, choice), so it works for every
 * file system without knowing about any of them.
 */
public final class ConnectDialog extends BasicWindow {

	private final Map<String, Supplier<String>> values = new LinkedHashMap<>();
	private final Label problems = new Label("");
	private boolean ok;

	private ConnectDialog(FileSourceFactory factory) {
		super("Connect: "+factory.getTypeId());
		setHints(Arrays.asList(Window.Hint.CENTERED, Window.Hint.MODAL));

		Properties initial = Connections.initialValues(factory);
		List<ConnectionSetting> settings = Connections.formSettings(factory);

		Panel form = new Panel(new GridLayout(2));
		if( settings.isEmpty()) {
			form.addComponent(new Label("Nothing to set up; choose Connect."));
			form.addComponent(new EmptySpace());
		}
		for(ConnectionSetting s : settings) {
			String value = initial.getProperty(s.key(), s.defaultValue() == null ? "" : s.defaultValue());
			form.addComponent(new Label(s.label()+(s.required() ? " *" : "")+(s.advanced() ? " (adv)" : "")));
			form.addComponent(editorFor(s, value));
		}

		Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL));
		buttons.addComponent(new Button("Connect", ()->{
			ok = true;
			close();
		}));
		buttons.addComponent(new Button("Cancel", this::close));

		Panel root = new Panel(new LinearLayout(Direction.VERTICAL));
		root.addComponent(form);
		root.addComponent(problems);
		root.addComponent(new Label("Enter: connect   Esc: cancel   Tab: next field"));
		root.addComponent(buttons);
		setComponent(root);
	}

	private com.googlecode.lanterna.gui2.Component editorFor(ConnectionSetting s, String value) {
		switch (s.kind()) {
			case BOOLEAN: {
				CheckBox box = new CheckBox();
				box.setChecked(Boolean.parseBoolean(value));
				values.put(s.key(), ()->Boolean.toString(box.isChecked()));
				return box;
			}
			case CHOICE: {
				ComboBox<String> box = new ComboBox<>(s.choices());
				int idx = s.choices().indexOf(value);
				if( idx >= 0 ) {
					box.setSelectedIndex(idx);
				}
				values.put(s.key(), ()->box.getSelectedItem());
				return box;
			}
			case SECRET: {
				TextBox box = new SubmitBox(value);
				box.setMask('*');
				values.put(s.key(), box::getText);
				return box;
			}
			case MULTILINE_SECRET: {
				// key material: several lines, so it can't be masked
				TextBox box = new TextBox(new TerminalSize(32, 4), value, TextBox.Style.MULTI_LINE);
				values.put(s.key(), box::getText);
				return box;
			}
			default: {
				TextBox box = new SubmitBox(value);
				if( s.kind() == ConnectionSetting.Kind.INTEGER ) {
					box.setValidationPattern(java.util.regex.Pattern.compile("-?[0-9]*"));
				}
				values.put(s.key(), box::getText);
				return box;
			}
		}
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
		return super.handleInput(key);
	}

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
	 * @return the values, or null if canceled
	 */
	public static Properties show(WindowBasedTextGUI gui, FileSourceFactory factory) {
		ConnectDialog dialog = new ConnectDialog(factory);
		while( true ) {
			dialog.ok = false;
			gui.addWindowAndWait(dialog);
			if( !dialog.ok ) {
				return null;
			}
			Properties p = dialog.collect();
			List<String> errors = factory.validateConnection(p);
			if( errors.isEmpty()) {
				return p;
			}
			dialog.problems.setText(String.join("\n", errors));
		}
	}
}
