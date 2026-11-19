#!/usr/bin/env python3
import sys
import os
import subprocess
import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk, Gdk

prompt = sys.argv[1] if len(sys.argv) > 1 else "SSH authentication"
credential_id = os.environ.get("RDT_SSH_CREDENTIAL_ID")
# Only answer SSH account password prompts; never answer key passphrase or host-key prompts.
if credential_id and prompt.lower().rstrip().endswith("password:") and "passphrase" not in prompt.lower():
    try:
        result = subprocess.run(["secret-tool", "lookup", "application", "remote-dev-terminal", "host", credential_id],
                                capture_output=True, text=True, timeout=30)
        if result.returncode == 0 and result.stdout:
            sys.stdout.write(result.stdout)
            sys.exit(0)
    except (OSError, subprocess.TimeoutExpired):
        pass

Gtk.Settings.get_default().set_property("gtk-application-prefer-dark-theme", True)
provider = Gtk.CssProvider()
provider.load_from_data(b"""
    window, dialog, dialog > box, dialog headerbar { background-image: none; background-color: #101823; color: #e8eef6; }
    entry, button { background-image: none; background-color: #16222f; color: #e8eef6; border: 1px solid #2b3e51; border-radius: 6px; padding: 4px 8px; }
    entry:focus, button:hover { border-color: #86dbb8; }
    button:hover { background-color: #213548; }
""")
Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
if os.environ.get("RDT_SSH_NONINTERACTIVE") == "1":
    sys.exit(1)

dialog = Gtk.Dialog(title="SSH", modal=True)
dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "确定", Gtk.ResponseType.OK)
box = dialog.get_content_area()
box.set_spacing(8)
box.set_border_width(12)
label = Gtk.Label(label=prompt, xalign=0)
label.set_line_wrap(True)
box.add(label)
entry = Gtk.Entry()
entry.set_visibility("yes/no" in prompt.lower() or "fingerprint" in prompt.lower())
entry.set_activates_default(True)
dialog.set_default_response(Gtk.ResponseType.OK)
box.add(entry)
dialog.show_all()
if dialog.run() == Gtk.ResponseType.OK:
    print(entry.get_text())
    sys.exit(0)
sys.exit(1)
