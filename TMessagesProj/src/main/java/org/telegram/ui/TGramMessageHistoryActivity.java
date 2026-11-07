package org.telegram.ui;

import android.view.View;
import android.text.format.DateFormat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.TrustedMessageArchive;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Date;

/** Local viewer for message snapshots captured before supported edit/delete updates. */
public class TGramMessageHistoryActivity extends UniversalFragment {

    private static final int ID_CLEAR = 1;
    private static final int ID_ITEM_BASE = 1000;

    @Override
    protected boolean isTGramPage() {
        return true;
    }

    @Override
    protected boolean useLargeTitle() {
        return org.telegram.messenger.TrustedConfig.isLargeTitles();
    }

    @Override
    protected CharSequence getTitle() {
        return "Message history";
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        JSONArray archived = TrustedMessageArchive.getArchivedMessages(currentAccount);
        items.add(UItem.asHeader("Saved on this device"));
        items.add(UItem.asShadow("Only message snapshots captured by TrustedGram are shown. Older messages cannot be recovered retroactively."));
        if (archived.length() == 0) {
            items.add(UItem.asHeader("No history yet"));
            items.add(UItem.asShadow("When a supported message edit or deletion update is received, its previous text is saved here."));
            return;
        }
        items.add(UItem.asButton(ID_CLEAR, "Clear message history", "Delete all locally archived snapshots"));
        items.add(UItem.asShadow(null));
        for (int i = archived.length() - 1; i >= 0; i--) {
            JSONObject entry = archived.optJSONObject(i);
            if (entry == null) continue;
            String event = entry.optString("event", "edited");
            String title = "deleted".equals(event) ? "Deleted message" : "Previous edited version";
            String body = entry.optString("text", "");
            if (body.trim().isEmpty()) body = entry.optBoolean("has_media", false) ? "Media message" : "Empty message";
            if (body.length() > 100) body = body.substring(0, 97) + "...";
            long timestamp = entry.optLong("date", 0) * 1000L;
            String date = timestamp > 0 ? DateFormat.getMediumDateFormat(getContext()).format(new Date(timestamp)) : "";
            String detail = body + (date.isEmpty() ? "" : "  •  " + date);
            items.add(UItem.asButton(ID_ITEM_BASE + i, title, detail));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CLEAR) {
            if (getParentActivity() == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Clear message history?");
            builder.setMessage("This removes all archived snapshots from this device. It cannot be undone.");
            builder.setPositiveButton("Clear", (dialog, which) -> {
                TrustedMessageArchive.clear(currentAccount);
                listView.adapter.update(true);
            });
            builder.setNegativeButton("Cancel", null);
            showDialog(builder.create());
            return;
        }
        if (item.id >= ID_ITEM_BASE) {
            int index = item.id - ID_ITEM_BASE;
            JSONObject entry = TrustedMessageArchive.getArchivedMessages(currentAccount).optJSONObject(index);
            if (entry == null || getParentActivity() == null) return;
            String event = entry.optString("event", "edited");
            String title = "deleted".equals(event) ? "Deleted message" : "Previous edited version";
            String text = entry.optString("text", "");
            if (text.isEmpty()) text = entry.optBoolean("has_media", false) ? "[Media message]" : "[Empty message]";
            String details = "\\n\\nChat ID: " + entry.optLong("dialog_id")
                    + "\\nMessage ID: " + entry.optInt("message_id");
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(title);
            builder.setMessage(text + details);
            builder.setPositiveButton("Close", null);
            showDialog(builder.create());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
