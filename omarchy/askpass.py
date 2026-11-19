#!/usr/bin/env python3
import sys
import os
import subprocess
import gi
gi.require_version("Gtk", "3.0")
from gi.repository import Gtk

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
